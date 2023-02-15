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
package org.neo4j.kernel.impl.transaction.log.entry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.neo4j.kernel.impl.transaction.log.entry.LogFormat.CURRENT_LOG_FORMAT_VERSION;
import static org.neo4j.kernel.impl.transaction.log.entry.LogHeaderReader.readLogHeader;
import static org.neo4j.kernel.impl.transaction.log.entry.LogHeaderWriter.LOG_VERSION_MASK;
import static org.neo4j.kernel.impl.transaction.log.entry.LogHeaderWriter.encodeLogVersion;
import static org.neo4j.kernel.impl.transaction.log.entry.LogSegments.UNKNOWN_LOG_SEGMENT_SIZE;
import static org.neo4j.memory.EmptyMemoryTracker.INSTANCE;
import static org.neo4j.storageengine.api.TransactionIdStore.BASE_TX_CHECKSUM;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.neo4j.io.fs.DefaultFileSystemAbstraction;
import org.neo4j.io.fs.StoreChannel;
import org.neo4j.io.memory.ByteBuffers;
import org.neo4j.kernel.impl.transaction.log.InMemoryClosableChannel;
import org.neo4j.kernel.impl.transaction.log.LogPosition;
import org.neo4j.storageengine.api.StoreId;
import org.neo4j.storageengine.api.StoreIdSerialization;
import org.neo4j.test.RandomSupport;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.RandomExtension;
import org.neo4j.test.extension.testdirectory.TestDirectoryExtension;
import org.neo4j.test.utils.TestDirectory;

@TestDirectoryExtension
@ExtendWith(RandomExtension.class)
class LogHeaderReaderTest {
    @Inject
    private DefaultFileSystemAbstraction fileSystem;

    @Inject
    private TestDirectory testDirectory;

    @Inject
    private RandomSupport random;

    private long expectedLogVersion;
    private long expectedTxId;
    private StoreId expectedStoreId;
    private int expectedSegmentSize;
    private int expectedChecksum;

    @BeforeEach
    void setUp() {
        expectedLogVersion = random.nextLong(0, LOG_VERSION_MASK);
        expectedTxId = random.nextLong();
        expectedStoreId = new StoreId(
                random.nextLong(),
                random.nextLong(),
                "engine-" + random.nextInt(0, 255),
                "format-" + random.nextInt(0, 255),
                random.nextInt(0, 127),
                random.nextInt(0, 127));
        expectedSegmentSize = random.nextInt(1, 666);
        expectedChecksum = random.nextInt();
    }

    @ParameterizedTest
    @MethodSource("allVersions")
    void shouldReadALogHeaderFromAByteChannel(TestCase testCase) throws IOException {
        var buffer = ByteBuffers.allocate(LogFormat.BIGGEST_HEADER, ByteOrder.BIG_ENDIAN, INSTANCE);
        testCase.write(
                buffer, expectedLogVersion, expectedTxId, expectedStoreId, expectedSegmentSize, expectedChecksum);

        try (var channel = new InMemoryClosableChannel(buffer.array(), true, true, ByteOrder.LITTLE_ENDIAN)) {
            assertThat(readLogHeader(channel, true, null, INSTANCE))
                    .isEqualTo(testCase.expected(
                            expectedLogVersion, expectedTxId, expectedStoreId, expectedSegmentSize, expectedChecksum));
        }
    }

    @ParameterizedTest
    @MethodSource("allVersions")
    void shouldReadALogHeaderFromAFile(TestCase testCase) throws IOException {
        var file = testDirectory.file("ReadLogHeader");

        var buffer = ByteBuffers.allocate(LogFormat.BIGGEST_HEADER, ByteOrder.BIG_ENDIAN, INSTANCE);
        testCase.write(
                buffer, expectedLogVersion, expectedTxId, expectedStoreId, expectedSegmentSize, expectedChecksum);

        try (var stream = fileSystem.openAsOutputStream(file, false)) {
            stream.write(buffer.array());
        }
        LogHeader expected = testCase.expected(
                expectedLogVersion, expectedTxId, expectedStoreId, expectedSegmentSize, expectedChecksum);
        assertThat(readLogHeader(fileSystem, file, INSTANCE)).isEqualTo(expected);
    }

    @Test
    void shouldFailWhenUnableToReadALogHeaderFromAChannel() {
        var buffer = ByteBuffers.allocate(1, ByteOrder.LITTLE_ENDIAN, INSTANCE);
        buffer.put((byte) 0xAF);

        try (var channel = new InMemoryClosableChannel(buffer.array(), true, true, ByteOrder.LITTLE_ENDIAN)) {
            assertThatThrownBy(() -> readLogHeader(channel, true, null, INSTANCE))
                    .isInstanceOf(IncompleteLogHeaderException.class);
        }
    }

    @Test
    void shouldFailWhenUnableToReadALogHeaderFromAFile() throws IOException {
        var file = testDirectory.file("ReadLogHeader");

        ((StoreChannel) fileSystem.write(file)).close();

        assertThatThrownBy(() -> readLogHeader(fileSystem, file, INSTANCE))
                .isInstanceOf(IncompleteLogHeaderException.class)
                .hasMessageContaining(file.getFileName().toString());
    }

    @Test
    void readEmptyPreallocatedFileHeaderAsNoHeader() throws IOException {
        try (var channel = new InMemoryClosableChannel(
                new byte[CURRENT_LOG_FORMAT_VERSION], true, true, ByteOrder.LITTLE_ENDIAN)) {
            assertThat(readLogHeader(channel, true, null, INSTANCE)).isNull();
        }
    }

    private static Stream<TestCase> allVersions() {
        return Stream.of(
                new TestCase(LogFormat.V6) {
                    @Override
                    public void write(
                            ByteBuffer buffer,
                            long logVersion,
                            long txId,
                            StoreId storeId,
                            int segmentSize,
                            int checksum) {
                        buffer.putLong(encodeLogVersion(logVersion, versionByte()));
                        buffer.putLong(txId);
                    }

                    @Override
                    LogHeader expected(
                            long logVersion, long previousCommittedTx, StoreId storeId, int segmentSize, int checksum) {
                        return new LogHeader(
                                (byte) 6,
                                new LogPosition(logVersion, 16),
                                previousCommittedTx,
                                null,
                                UNKNOWN_LOG_SEGMENT_SIZE,
                                BASE_TX_CHECKSUM);
                    }
                },
                new TestCase(LogFormat.V7) {
                    @Override
                    void write(
                            ByteBuffer buffer,
                            long logVersion,
                            long txId,
                            StoreId storeId,
                            int segmentSize,
                            int checksum) {
                        buffer.putLong(encodeLogVersion(logVersion, versionByte()));
                        buffer.putLong(txId);
                        buffer.putLong(0); // legacy creation time
                        buffer.putLong(0); // legacy random
                        buffer.putLong(0); // legacy store version
                        buffer.putLong(0); // legacy upgrade time
                        buffer.putLong(0); // legacy upgrade tx id
                        buffer.putLong(0); // reserved
                    }

                    @Override
                    LogHeader expected(
                            long logVersion, long previousCommittedTx, StoreId storeId, int segmentSize, int checksum) {
                        return new LogHeader(
                                (byte) 7,
                                new LogPosition(logVersion, 64),
                                previousCommittedTx,
                                null,
                                UNKNOWN_LOG_SEGMENT_SIZE,
                                BASE_TX_CHECKSUM);
                    }
                },
                new TestCase(LogFormat.V8) {
                    @Override
                    void write(
                            ByteBuffer buffer,
                            long logVersion,
                            long txId,
                            StoreId storeId,
                            int segmentSize,
                            int checksum)
                            throws IOException {
                        buffer.putLong(encodeLogVersion(logVersion, versionByte()));
                        buffer.putLong(txId);
                        StoreIdSerialization.serializeWithFixedSize(storeId, buffer);
                        buffer.putLong(0); // reserved
                        buffer.putLong(0); // reserved
                        buffer.putLong(0); // reserved
                        buffer.putLong(0); // reserved
                        buffer.putLong(0); // reserved
                        buffer.putLong(0); // reserved
                    }

                    @Override
                    LogHeader expected(
                            long logVersion, long previousCommittedTx, StoreId storeId, int segmentSize, int checksum) {
                        return new LogHeader(
                                (byte) 8,
                                new LogPosition(logVersion, 128),
                                previousCommittedTx,
                                storeId,
                                UNKNOWN_LOG_SEGMENT_SIZE,
                                BASE_TX_CHECKSUM);
                    }
                },
                new TestCase(LogFormat.V9) {
                    @Override
                    void write(
                            ByteBuffer buffer,
                            long logVersion,
                            long txId,
                            StoreId storeId,
                            int segmentSize,
                            int checksum)
                            throws IOException {
                        buffer.putLong(encodeLogVersion(logVersion, versionByte()));
                        buffer.putLong(txId);
                        StoreIdSerialization.serializeWithFixedSize(storeId, buffer);
                        buffer.putInt(segmentSize);
                        buffer.putInt(checksum);
                        buffer.putLong(0); // reserved
                        buffer.putLong(0); // reserved
                        buffer.putLong(0); // reserved
                        buffer.putLong(0); // reserved
                        buffer.putLong(0); // reserved
                    }

                    @Override
                    LogHeader expected(
                            long logVersion, long previousCommittedTx, StoreId storeId, int segmentSize, int checksum) {
                        return new LogHeader(
                                (byte) 9,
                                new LogPosition(logVersion, segmentSize),
                                previousCommittedTx,
                                storeId,
                                segmentSize,
                                checksum);
                    }
                });
    }

    private abstract static class TestCase {
        private final LogFormat format;

        TestCase(LogFormat format) {
            this.format = format;
        }

        abstract void write(
                ByteBuffer buffer, long logVersion, long txId, StoreId storeId, int segmentSize, int checksum)
                throws IOException;

        abstract LogHeader expected(
                long logVersion, long previousCommittedTx, StoreId storeId, int segmentSize, int checksum);

        byte versionByte() {
            return format.getVersionByte();
        }

        @Override
        public String toString() {
            return format.name();
        }
    }
}
