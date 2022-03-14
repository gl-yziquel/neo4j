/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.upgrade;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.neo4j.common.ProgressReporter;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.consistency.checking.full.ConsistencyCheckIncompleteException;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.exceptions.KernelException;
import org.neo4j.internal.batchimport.BatchImporterFactory;
import org.neo4j.internal.batchimport.IndexImporterFactory;
import org.neo4j.internal.recordstorage.RecordStorageEngineFactory;
import org.neo4j.io.ByteUnit;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.io.layout.Neo4jLayout;
import org.neo4j.io.layout.recordstorage.RecordDatabaseLayout;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.context.CursorContextFactory;
import org.neo4j.io.pagecache.tracing.DefaultPageCacheTracer;
import org.neo4j.io.pagecache.tracing.PageCacheTracer;
import org.neo4j.kernel.api.impl.index.SchemaIndexMigrator;
import org.neo4j.kernel.api.index.IndexProvider;
import org.neo4j.kernel.database.DatabaseTracers;
import org.neo4j.kernel.impl.store.format.RecordFormatSelector;
import org.neo4j.kernel.impl.store.format.RecordFormats;
import org.neo4j.kernel.impl.store.format.standard.StandardV4_3;
import org.neo4j.kernel.impl.storemigration.LogsMigrator;
import org.neo4j.kernel.impl.storemigration.MigrationTestUtils;
import org.neo4j.kernel.impl.storemigration.RecordStorageMigrator;
import org.neo4j.kernel.impl.storemigration.RecordStoreVersionCheck;
import org.neo4j.kernel.impl.storemigration.StoreUpgrader;
import org.neo4j.kernel.impl.transaction.log.LogTailMetadata;
import org.neo4j.kernel.recovery.LogTailExtractor;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.logging.internal.LogService;
import org.neo4j.logging.internal.NullLogService;
import org.neo4j.scheduler.JobScheduler;
import org.neo4j.storageengine.api.StorageEngineFactory;
import org.neo4j.storageengine.api.StoreVersion;
import org.neo4j.storageengine.api.StoreVersionCheck;
import org.neo4j.storageengine.migration.MigrationProgressMonitor;
import org.neo4j.storageengine.migration.StoreMigrationParticipant;
import org.neo4j.test.TestDatabaseManagementServiceBuilder;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.pagecache.PageCacheSupportExtension;
import org.neo4j.test.extension.testdirectory.TestDirectoryExtension;
import org.neo4j.test.scheduler.ThreadPoolJobScheduler;
import org.neo4j.test.utils.TestDirectory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.neo4j.configuration.GraphDatabaseSettings.DEFAULT_DATABASE_NAME;
import static org.neo4j.configuration.GraphDatabaseSettings.allow_upgrade;
import static org.neo4j.consistency.store.StoreAssertions.assertConsistentStore;
import static org.neo4j.io.pagecache.context.CursorContextFactory.NULL_CONTEXT_FACTORY;
import static org.neo4j.io.pagecache.context.EmptyVersionContextSupplier.EMPTY;
import static org.neo4j.io.pagecache.tracing.PageCacheTracer.NULL;
import static org.neo4j.kernel.impl.storemigration.MigrationTestUtils.checkNeoStoreHasFormatVersion;
import static org.neo4j.memory.EmptyMemoryTracker.INSTANCE;

@TestDirectoryExtension
@Disabled
public class StoreUpgraderInterruptionTestIT
{
    private static final Config CONFIG = Config.defaults( GraphDatabaseSettings.pagecache_memory, ByteUnit.mebiBytes( 8 ) );
    private final BatchImporterFactory batchImporterFactory = BatchImporterFactory.withHighestPriority();

    @RegisterExtension
    static PageCacheSupportExtension pageCacheExtension = new PageCacheSupportExtension();
    @Inject
    private TestDirectory directory;
    @Inject
    private FileSystemAbstraction fs;

    private static Stream<Arguments> versions()
    {
        return Stream.of( Arguments.of( StandardV4_3.STORE_VERSION ) );
    }

    private JobScheduler jobScheduler;
    private Neo4jLayout neo4jLayout;
    private RecordDatabaseLayout workingDatabaseLayout;
    private Path prepareDirectory;
    private PageCache pageCache;
    private RecordFormats baselineFormat;
    private RecordFormats successorFormat;

    public void init( String version )
    {
        jobScheduler = new ThreadPoolJobScheduler();
        neo4jLayout = Neo4jLayout.of( directory.homePath() );
        workingDatabaseLayout = RecordDatabaseLayout.of( neo4jLayout, DEFAULT_DATABASE_NAME );
        prepareDirectory = directory.directory( "prepare" );
        pageCache = pageCacheExtension.getPageCache( fs );
        baselineFormat = RecordFormatSelector.selectForVersion( version );
        successorFormat = RecordFormatSelector.findLatestSupportedFormatInFamily( baselineFormat ).orElse( baselineFormat );
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        jobScheduler.close();
    }

    @ParameterizedTest
    @MethodSource( "versions" )
    public void shouldSucceedWithUpgradeAfterPreviousAttemptDiedDuringMigration( String version )
            throws IOException, ConsistencyCheckIncompleteException
    {
        init( version );
        MigrationTestUtils.prepareSampleLegacyDatabase( version, fs, workingDatabaseLayout, prepareDirectory );
        var contextFactory = NULL_CONTEXT_FACTORY;
        RecordStoreVersionCheck versionCheck =
                new RecordStoreVersionCheck( fs, pageCache, workingDatabaseLayout, NullLogProvider.getInstance(), Config.defaults(), contextFactory );
        MigrationProgressMonitor progressMonitor = MigrationProgressMonitor.SILENT;
        LogService logService = NullLogService.getInstance();
        RecordStorageMigrator failingStoreMigrator = new RecordStorageMigrator( fs, pageCache, PageCacheTracer.NULL, CONFIG, logService, jobScheduler,
                contextFactory, batchImporterFactory, INSTANCE )
        {
            @Override
            public void migrate( DatabaseLayout directoryLayout, DatabaseLayout migrationLayout,
                    ProgressReporter progressReporter, StoreVersion fromVersion, StoreVersion toVersion,
                    IndexImporterFactory indexImporterFactory, LogTailMetadata tailMetadata ) throws IOException, KernelException
            {
                super.migrate( directoryLayout, migrationLayout, progressReporter, fromVersion, toVersion, indexImporterFactory, tailMetadata );
                throw new RuntimeException( "This upgrade is failing" );
            }
        };

        try
        {
            newUpgrader( versionCheck, progressMonitor, createIndexMigrator( contextFactory ), failingStoreMigrator )
                    .migrateIfNeeded( workingDatabaseLayout );
            fail( "Should throw exception" );
        }
        catch ( RuntimeException e )
        {
            assertEquals( "This upgrade is failing", e.getMessage() );
        }

        assertTrue( checkNeoStoreHasFormatVersion( versionCheck, baselineFormat ) );

        RecordStorageMigrator migrator =
                new RecordStorageMigrator( fs, pageCache, NULL, CONFIG, logService, jobScheduler, contextFactory, batchImporterFactory, INSTANCE );
        SchemaIndexMigrator indexMigrator = createIndexMigrator( contextFactory );
        newUpgrader( versionCheck, progressMonitor, indexMigrator, migrator ).migrateIfNeeded( workingDatabaseLayout );

        assertTrue( checkNeoStoreHasFormatVersion( versionCheck, successorFormat ) );

        // Since consistency checker is in read only mode we need to start/stop db to generate label scan store.
        startStopDatabase( neo4jLayout.homeDirectory() );
        assertConsistentStore( workingDatabaseLayout );
    }

    private SchemaIndexMigrator createIndexMigrator( CursorContextFactory contextFactory )
    {
        return new SchemaIndexMigrator( "upgrade test indexes", fs, pageCache, IndexProvider.EMPTY.directoryStructure(),
                StorageEngineFactory.defaultStorageEngine(), true, contextFactory );
    }

    @ParameterizedTest
    @MethodSource( "versions" )
    public void tracePageCacheAccessOnIdStoreUpgrade( String version ) throws IOException, ConsistencyCheckIncompleteException
    {
        init( version );
        MigrationTestUtils.prepareSampleLegacyDatabase( version, fs, workingDatabaseLayout, prepareDirectory );
        var contextFactory = NULL_CONTEXT_FACTORY;
        RecordStoreVersionCheck versionCheck = new RecordStoreVersionCheck( fs, pageCache, workingDatabaseLayout, NullLogProvider.getInstance(),
                Config.defaults(), contextFactory );
        MigrationProgressMonitor progressMonitor = MigrationProgressMonitor.SILENT;
        LogService logService = NullLogService.getInstance();
        var idMigratorTracer = new DefaultPageCacheTracer();
        var recordMigratorTracer = new DefaultPageCacheTracer();

        assertTrue( checkNeoStoreHasFormatVersion( versionCheck, baselineFormat ) );

        var migrator = new RecordStorageMigrator( fs, pageCache, NULL, CONFIG, logService, jobScheduler, contextFactory, batchImporterFactory,
                INSTANCE );
        newUpgrader( versionCheck, progressMonitor, createIndexMigrator(contextFactory), migrator ).migrateIfNeeded( workingDatabaseLayout );

        assertTrue( checkNeoStoreHasFormatVersion( versionCheck, successorFormat ) );

        startStopDatabase( neo4jLayout.homeDirectory() );
        assertConsistentStore( workingDatabaseLayout );

        assertEquals( 126, idMigratorTracer.pins() );
        assertEquals( 126, idMigratorTracer.unpins() );

        assertEquals( 231, recordMigratorTracer.pins() );
        assertEquals( 231, recordMigratorTracer.unpins() );
    }

    @ParameterizedTest
    @MethodSource( "versions" )
    public void shouldSucceedWithUpgradeAfterPreviousAttemptDiedDuringMovingFiles( String version )
            throws IOException, ConsistencyCheckIncompleteException
    {
        init( version );
        MigrationTestUtils.prepareSampleLegacyDatabase( version, fs, workingDatabaseLayout, prepareDirectory );
        var contextFactory = NULL_CONTEXT_FACTORY;
        RecordStoreVersionCheck versionCheck = new RecordStoreVersionCheck( fs, pageCache, workingDatabaseLayout, NullLogProvider.getInstance(),
                Config.defaults(), contextFactory );
        MigrationProgressMonitor progressMonitor = MigrationProgressMonitor.SILENT;
        LogService logService = NullLogService.getInstance();
        RecordStorageMigrator failingStoreMigrator = new RecordStorageMigrator( fs, pageCache, NULL, CONFIG, logService, jobScheduler, contextFactory,
                batchImporterFactory, INSTANCE )
        {
            @Override
            public void moveMigratedFiles( DatabaseLayout migrationLayout, DatabaseLayout directoryLayout, String versionToUpgradeFrom,
                    String versionToMigrateTo ) throws IOException
            {
                super.moveMigratedFiles( migrationLayout, directoryLayout, versionToUpgradeFrom, versionToMigrateTo );
                throw new RuntimeException( "This upgrade is failing" );
            }
        };

        assertTrue( checkNeoStoreHasFormatVersion( versionCheck, baselineFormat ) );

        try
        {
            newUpgrader( versionCheck, progressMonitor, createIndexMigrator( contextFactory ), failingStoreMigrator )
                    .migrateIfNeeded( workingDatabaseLayout );
            fail( "Should throw exception" );
        }
        catch ( RuntimeException e )
        {
            assertEquals( "This upgrade is failing", e.getMessage() );
        }

        RecordStorageMigrator migrator = new RecordStorageMigrator( fs, pageCache, NULL, CONFIG, logService, jobScheduler, contextFactory,
                batchImporterFactory, INSTANCE );
        newUpgrader( versionCheck, progressMonitor, createIndexMigrator( contextFactory ), migrator ).migrateIfNeeded( workingDatabaseLayout );

        assertTrue( checkNeoStoreHasFormatVersion( versionCheck, successorFormat ) );

        pageCache.close();

        // Since consistency checker is in read only mode we need to start/stop db to generate label scan store.
        startStopDatabase( neo4jLayout.homeDirectory() );
        assertConsistentStore( workingDatabaseLayout );
    }

    private StoreUpgrader newUpgrader( StoreVersionCheck versionCheck, MigrationProgressMonitor progressMonitor, StoreMigrationParticipant... participants )
            throws IOException
    {
        Config config = Config.defaults( allow_upgrade, true );

        RecordStorageEngineFactory storageEngineFactory = new RecordStorageEngineFactory();
        CursorContextFactory contextFactory = new CursorContextFactory( NULL, EMPTY );
        var logTail = loadLogTail( workingDatabaseLayout, config, storageEngineFactory );
        Supplier<LogTailMetadata> logTailSupplier = () -> logTail;
        LogsMigrator logsUpgrader =
                new LogsMigrator( fs, storageEngineFactory, storageEngineFactory, workingDatabaseLayout, pageCache, config, contextFactory, logTailSupplier );
        StoreUpgrader upgrader =
                new StoreUpgrader( storageEngineFactory, versionCheck, progressMonitor, config, fs, NullLogProvider.getInstance(), logsUpgrader,
                        contextFactory, logTailSupplier );
        for ( StoreMigrationParticipant participant : participants )
        {
            upgrader.addParticipant( participant );
        }
        return upgrader;
    }

    private LogTailMetadata loadLogTail( DatabaseLayout layout, Config config, StorageEngineFactory engineFactory ) throws IOException
    {
        return new LogTailExtractor( fs, pageCache, config, engineFactory, DatabaseTracers.EMPTY ).getTailMetadata( layout, INSTANCE );
    }

    private static void startStopDatabase( Path storeDir )
    {
        DatabaseManagementService managementService = new TestDatabaseManagementServiceBuilder( storeDir ).setConfig( allow_upgrade, true ).build();
        managementService.database( DEFAULT_DATABASE_NAME );
        managementService.shutdown();
    }
}
