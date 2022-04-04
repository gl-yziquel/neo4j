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
package org.neo4j.graphdb.factory.module.id;

import org.eclipse.collections.impl.factory.primitive.LongSets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;

import org.neo4j.configuration.Config;
import org.neo4j.internal.id.BufferedIdController;
import org.neo4j.internal.id.BufferingIdGeneratorFactory;
import org.neo4j.internal.id.FreeIds;
import org.neo4j.internal.id.IdController;
import org.neo4j.internal.id.IdGenerator;
import org.neo4j.internal.id.IdGeneratorFactory;
import org.neo4j.internal.recordstorage.RecordIdType;
import org.neo4j.io.fs.DefaultFileSystemAbstraction;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.context.CursorContextFactory;
import org.neo4j.io.pagecache.tracing.DefaultPageCacheTracer;
import org.neo4j.io.pagecache.tracing.PageCacheTracer;
import org.neo4j.kernel.lifecycle.LifeSupport;
import org.neo4j.memory.EmptyMemoryTracker;
import org.neo4j.scheduler.JobScheduler;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.LifeExtension;
import org.neo4j.test.extension.pagecache.PageCacheExtension;
import org.neo4j.test.utils.TestDirectory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.collections.api.factory.Sets.immutable;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.neo4j.configuration.Config.defaults;
import static org.neo4j.dbms.database.readonly.DatabaseReadOnlyChecker.writable;
import static org.neo4j.internal.id.IdSlotDistribution.SINGLE_IDS;
import static org.neo4j.io.pagecache.context.CursorContext.NULL_CONTEXT;
import static org.neo4j.io.pagecache.context.EmptyVersionContextSupplier.EMPTY;
import static org.neo4j.kernel.database.DatabaseIdFactory.from;

@PageCacheExtension
@ExtendWith( LifeExtension.class )
class IdContextFactoryBuilderTest
{
    private static final CursorContextFactory CONTEXT_FACTORY = new CursorContextFactory( PageCacheTracer.NULL, EMPTY );

    @Inject
    private TestDirectory testDirectory;
    @Inject
    private DefaultFileSystemAbstraction fs;
    @Inject
    private PageCache pageCache;
    @Inject
    private LifeSupport life;
    private final JobScheduler jobScheduler = mock( JobScheduler.class );

    @Test
    void requireFileSystemWhenIdGeneratorFactoryNotProvided()
    {
        NullPointerException exception =
                assertThrows( NullPointerException.class, () -> IdContextFactoryBuilder.of( null, jobScheduler, null ).build() );
        assertThat( exception.getMessage() ).contains( "File system is required" );
    }

    @Test
    void createContextWithCustomIdGeneratorFactoryWhenProvided() throws IOException
    {
        IdGeneratorFactory idGeneratorFactory = mock( IdGeneratorFactory.class );
        Config config = defaults();
        IdContextFactory contextFactory =
                IdContextFactoryBuilder.of( fs, jobScheduler, config ).withIdGenerationFactoryProvider(
                        any -> idGeneratorFactory ).build();
        DatabaseIdContext idContext = contextFactory.createIdContext( from( "database", UUID.randomUUID() ), CONTEXT_FACTORY );

        IdGeneratorFactory bufferedGeneratorFactory = idContext.getIdGeneratorFactory();
        assertThat( idContext.getIdController() ).isInstanceOf( BufferedIdController.class );
        assertThat( bufferedGeneratorFactory ).isInstanceOf( BufferingIdGeneratorFactory.class );

        ((BufferingIdGeneratorFactory) bufferedGeneratorFactory).initialize( fs, testDirectory.file( "buffer" ), config,
                () -> new IdController.TransactionSnapshot( LongSets.immutable.empty(), 0, 0 ), s -> true, EmptyMemoryTracker.INSTANCE );
        life.add( idContext.getIdController() );
        Path file = testDirectory.file( "a" );
        RecordIdType idType = RecordIdType.NODE;
        LongSupplier highIdSupplier = () -> 0;
        int maxId = 100;

        idGeneratorFactory.open( pageCache, file, idType, highIdSupplier, maxId, writable(), config, CONTEXT_FACTORY, immutable.empty(), SINGLE_IDS );

        verify( idGeneratorFactory ).open( pageCache, file, idType, highIdSupplier, maxId, writable(), config, CONTEXT_FACTORY, immutable.empty(), SINGLE_IDS );
    }

    @Test
    void createContextWithFactoryWrapper()
    {
        IdGeneratorFactory idGeneratorFactory = mock( IdGeneratorFactory.class );
        Function<IdGeneratorFactory,IdGeneratorFactory> factoryWrapper = ignored -> idGeneratorFactory;

        IdContextFactory contextFactory = IdContextFactoryBuilder.of( fs, jobScheduler, defaults() )
                                        .withFactoryWrapper( factoryWrapper )
                                        .build();

        DatabaseIdContext idContext = contextFactory.createIdContext( from( "database", UUID.randomUUID() ), CONTEXT_FACTORY );

        assertSame( idGeneratorFactory, idContext.getIdGeneratorFactory() );
    }

    @Test
    void useProvidedPageCacheCursorOnIdMaintenance() throws IOException
    {
        PageCacheTracer cacheTracer = new DefaultPageCacheTracer();
        CursorContextFactory contextFactory = new CursorContextFactory( cacheTracer, EMPTY );
        Config config = defaults();
        var idContextFactory = IdContextFactoryBuilder.of( fs, jobScheduler, config ).build();
        var idContext = idContextFactory.createIdContext( from( "test", UUID.randomUUID() ), contextFactory );
        var idGeneratorFactory = idContext.getIdGeneratorFactory();
        var idController = idContext.getIdController();
        idController.initialize( fs, testDirectory.file( "buffer" ), config, () -> new IdController.TransactionSnapshot( LongSets.immutable.empty(), 0, 0 ),
                s -> true, EmptyMemoryTracker.INSTANCE );
        life.add( idController );

        Path file = testDirectory.file( "b" );
        RecordIdType idType = RecordIdType.NODE;

        try ( IdGenerator idGenerator = idGeneratorFactory.create( pageCache, file, idType, 1, false, 100, writable(), config, contextFactory,
                immutable.empty(), SINGLE_IDS ) )
        {
            idGenerator.start( FreeIds.NO_FREE_IDS, NULL_CONTEXT );
            try ( var marker = idGenerator.marker( NULL_CONTEXT ) )
            {
                marker.markDeleted( 1 );
            }
            idGeneratorFactory.clearCache( NULL_CONTEXT );

            long initialPins = cacheTracer.pins();
            long initialUnpins = cacheTracer.unpins();
            long initialHits = cacheTracer.hits();

            idController.maintenance();

            assertThat( cacheTracer.pins() - initialPins ).isGreaterThan( 0 );
            assertThat( cacheTracer.unpins() - initialUnpins ).isGreaterThan( 0 );
            assertThat( cacheTracer.hits() - initialHits ).isGreaterThan( 0 );
        }
    }
}
