/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.qp.physicaloperator;

import com.akiban.ais.model.GroupTable;
import com.akiban.qp.row.ManagedRow;
import com.akiban.qp.row.RowHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

class IndexLookup_Default extends PhysicalOperator
{
    // PhysicalOperator interface

    @Override
    public OperatorExecution instantiate(StoreAdapter adapter, OperatorExecution[] ops)
    {
        ops[operatorId] = new Execution(adapter, inputOperator.instantiate(adapter, ops));
        return ops[operatorId];
    }

    @Override
    public void assignOperatorIds(AtomicInteger idGenerator)
    {
        inputOperator.assignOperatorIds(idGenerator);
        super.assignOperatorIds(idGenerator);
    }

    @Override
    public List<PhysicalOperator> getInputOperators()
    {
        List<PhysicalOperator> result = new ArrayList<PhysicalOperator>(1);
        result.add(inputOperator);
        return result;
    }

    @Override
    public String toString()
    {
        return String.format("%s(%s limit %s", getClass().getSimpleName(), groupTable, limit);
    }

    // IndexLookup_Default interface

    public IndexLookup_Default(PhysicalOperator inputOperator,
                               GroupTable groupTable,
                               Limit limit)
    {
        this.inputOperator = inputOperator;
        this.groupTable = groupTable;
        this.limit = limit;
    }

    // Class state

    private static final Logger LOG = LoggerFactory.getLogger(IndexLookup_Default.class);

    // Object state

    private final PhysicalOperator inputOperator;
    private final GroupTable groupTable;
    private final Limit limit;

    // Inner classes

    private class Execution extends SingleRowCachingCursor
    {
        // Cursor interface

        @Override
        public void open()
        {
            indexInput.open();
            advanceIndex();
        }

        @Override
        public boolean next()
        {
            if (first) {
                first = false;
            } else {
                if (groupCursor.next()) {
                    groupRow.set(groupCursor.currentRow());
                } else {
                    advanceIndex();
                }
            }
            outputRow(groupRow.managedRow());
            if (LOG.isInfoEnabled()) {
                LOG.info("IndexLookup: {}", groupRow.isNull() ? null : groupRow.managedRow());
            }
            return groupRow.isNotNull();
        }

        @Override
        public void close()
        {
            outputRow(null);
            indexInput.close();
            indexRow.set(null);
            groupCursor.close();
            groupRow.set(null);
        }

        // For use by this class

        private void advanceIndex()
        {
            groupRow.set(null);
            groupCursor.close();
            if (indexInput.next()) {
                indexRow.set(indexInput.currentRow());
                groupCursor.bind(this.indexRow.hKey());
                groupCursor.open();
                if (groupCursor.next()) {
                    ManagedRow currentRow = groupCursor.currentRow();
                    if (limit.limitReached(currentRow)) {
                        close();
                    } else {
                        groupRow.set(currentRow);
                    }
                }
            } else {
                indexRow.set(null);
            }
        }

        // Execution interface

        Execution(StoreAdapter adapter, OperatorExecution input)
        {
            super(adapter);
            this.indexInput = input;
            this.groupCursor = adapter.newGroupCursor(groupTable);
        }

        // Object state

        private final Cursor indexInput;
        private final RowHolder<ManagedRow> indexRow = new RowHolder<ManagedRow>();
        private final GroupCursor groupCursor;
        private final RowHolder<ManagedRow> groupRow = new RowHolder<ManagedRow>();
        private boolean first = true;
    }
}
