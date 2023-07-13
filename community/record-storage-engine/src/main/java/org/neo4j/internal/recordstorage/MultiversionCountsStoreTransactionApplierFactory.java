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
package org.neo4j.internal.recordstorage;

import org.neo4j.counts.CountsStore;
import org.neo4j.internal.counts.RelationshipGroupDegreesStore;
import org.neo4j.storageengine.api.CommandBatchToApply;
import org.neo4j.storageengine.api.TransactionApplicationMode;

class MultiversionCountsStoreTransactionApplierFactory implements TransactionApplierFactory {
    private final TransactionApplicationMode mode;
    private final CountsStore countsStore;
    private final RelationshipGroupDegreesStore groupDegreesStore;

    MultiversionCountsStoreTransactionApplierFactory(
            TransactionApplicationMode mode, CountsStore countsStore, RelationshipGroupDegreesStore groupDegreesStore) {
        this.mode = mode;
        this.countsStore = countsStore;
        this.groupDegreesStore = groupDegreesStore;
    }

    @Override
    public TransactionApplier startTx(CommandBatchToApply transaction, BatchContext batchContext) {
        if (mode.isReverseStep()) {
            return new SimpleCountsStoreTransactionApplier(
                    () -> countsStore.reverseUpdater(transaction.transactionId(), transaction.cursorContext()),
                    () -> groupDegreesStore.reverseUpdater(transaction.transactionId(), transaction.cursorContext()));
        }
        return new SimpleCountsStoreTransactionApplier(
                () -> countsStore.apply(
                        transaction.transactionId(), transaction.commandBatch().isLast(), transaction.cursorContext()),
                () -> groupDegreesStore.apply(
                        transaction.transactionId(), transaction.commandBatch().isLast(), transaction.cursorContext()));
    }
}
