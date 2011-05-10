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

import com.akiban.ais.model.Join;
import com.akiban.ais.model.UserTable;
import com.akiban.qp.row.Row;
import com.akiban.qp.rowtype.RowType;
import com.akiban.qp.rowtype.Schema;
import com.akiban.qp.rowtype.UserTableRowType;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

class Extract_Default extends PhysicalOperator
{
    // Object interface

    @Override
    public String toString()
    {
        return String.format("%s(%s)", getClass().getSimpleName(), extractTypes);
    }

    // PhysicalOperator interface

    @Override
    public Cursor cursor(StoreAdapter adapter)
    {
        return new Execution(adapter, inputOperator.cursor(adapter));
    }

    @Override
    public String describePlan()
    {
        return describePlan(inputOperator);
    }

    // GroupScan_Default interface

    public Extract_Default(Schema schema, PhysicalOperator inputOperator, Collection<RowType> extractTypes)
    {
        this.inputOperator = inputOperator;
        for (RowType cutType : extractTypes) {
            if (cutType instanceof UserTableRowType) {
                addDescendentTypes(schema, ((UserTableRowType) cutType).userTable(), this.extractTypes);
            } else {
                extractTypes.add(cutType);
            }
        }
    }

    // For use by this class

    private static void addDescendentTypes(Schema schema, UserTable table, Set<RowType> rowTypes)
    {
        rowTypes.add(schema.userTableRowType(table));
        for (Join join : table.getChildJoins()) {
            addDescendentTypes(schema, join.getChild(), rowTypes);
        }
    }

    // Object state

    private final PhysicalOperator inputOperator;
    private final Set<RowType> extractTypes = new HashSet<RowType>();

    // Inner classes

    private class Execution extends SingleRowCachingCursor
    {
        // Cursor interface

        @Override
        public void open(Bindings bindings)
        {
            input.open(bindings);
            next = input.next();
        }

        @Override
        public boolean next()
        {
            Row row = null;
            while (next && row == null) {
                row = input.currentRow();
                if (!extractTypes.contains(row.rowType())) {
                    row = null;
                }
                next = input.next();
            }
            outputRow(row);
            if (row == null) {
                close();
            }
            return row != null;
        }

        @Override
        public void close()
        {
            outputRow(null);
            input.close();
        }

        // Execution interface

        Execution(StoreAdapter adapter, Cursor input)
        {
            this.input = input;
        }

        // Object state

        private final Cursor input;
        private boolean next;
    }
}