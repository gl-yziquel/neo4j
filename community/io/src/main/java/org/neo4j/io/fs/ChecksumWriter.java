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
package org.neo4j.io.fs;

import java.io.IOException;
import java.util.function.Supplier;
import java.util.zip.CRC32C;
import java.util.zip.Checksum;

public interface ChecksumWriter {
    /**
     * Even though the {@link Checksum#getValue()} returns a long, this is currently casted down to an integer.
     */
    Supplier<Checksum> CHECKSUM_FACTORY = CRC32C::new;

    /**
     * Begin a new checksum segment.
     */
    void beginChecksumForWriting();

    /**
     * Calculate the checksum of the produced stream, since the last call to {@link #beginChecksumForWriting()}, and insert it at the end of the channel.
     *
     * @return the calculated checksum.
     * @throws IOException I/O error from channel.
     */
    int putChecksum() throws IOException;
}
