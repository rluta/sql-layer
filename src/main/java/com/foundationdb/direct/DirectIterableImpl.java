/**
 * Copyright (C) 2009-2013 FoundationDB, LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.foundationdb.direct;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.foundationdb.sql.embedded.JDBCResultSet;

/**
 * Very kludgey implementation by constructing SQL strings.
 * 
 * @author peter
 * 
 * @param <T>
 */
public class DirectIterableImpl<T> implements DirectIterable<T> {

    private final Class<T> clazz;
    private final DirectObject parent;
    private boolean hasNext;

    private final String table;
    private final List<String> predicates = new ArrayList<String>();
    private final List<String> sorts = new ArrayList<String>();
    private String limit;

    private boolean initialized;
    private String sql;

    private JDBCResultSet resultSet;

    public DirectIterableImpl(Class<T> ifaceClass, String toTable, DirectObject parent) {
        this.clazz = ifaceClass;
        this.table = toTable;
        this.parent = parent;
    }

    @Override
    public Iterator<T> iterator() {
        return new Iterator<T>() {

            @Override
            public boolean hasNext() {
                initIfNeeded();
                hasNext = nextRow();
                return hasNext;
            }

            @SuppressWarnings("unchecked")
            @Override
            public T next() {
                initIfNeeded();
                if (!hasNext) {
                    return null;
                }
                T result;
                try {
                    result = (T) resultSet.getEntity(clazz);
                } catch (SQLException e) {
                    throw new DirectException(e);
                }
                return result;
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException("Can't remove yet");
            }
        };
    }

    @SuppressWarnings("unchecked")
    public T single() {
        initIfNeeded();
        if (nextRow()) {
            try {
                T result = (T) resultSet.getEntity(clazz);
                return result;
            } catch (SQLException e) {
                throw new DirectException(e);
            }
        }
        return null;
    }

    private boolean nextRow() {
        try {
            boolean result = resultSet.next();
            return result;
        } catch (SQLException e) {
            throw new DirectException(e);
        }
    }

    private void initIfNeeded() {
        if (!initialized) {
            StringBuilder sb = new StringBuilder();
            sb.append("select * from ").append(table);
            if (!predicates.isEmpty()) {
                sb.append(" where ");
                boolean and = false;
                for (String p : predicates) {
                    if (and) {
                        sb.append(" and ");
                    }
                    sb.append(p);
                    and = true;
                }
            }
            boolean orderBy = false;
            for (final String sort : sorts) {
                sb.append(orderBy ? ", " : " order by ").append(sort);
                orderBy = true;
            }
            if (limit != null) {
                sb.append(" limit ").append(limit);
            }

            try {
                Statement statement = Direct.getContext().createStatement();
                sql = sb.toString();
                resultSet = (JDBCResultSet) statement.executeQuery(sql);
            } catch (SQLException e) {
                throw new DirectException(e);
            }
            initialized = true;
        }
    }

    @Override
    public DirectIterableImpl<T> where(final String predicate) {
        predicates.add(predicate);
        return this;
    }

    @Override
    public DirectIterableImpl<T> where(final String columnName, Object literal) {
        StringBuilder sb = new StringBuilder(columnName).append(" = ");
        if (literal instanceof Number) {
            sb.append(literal);
        } else {
            sb.append('\'').append(literal.toString()).append('\'');
        }
        predicates.add(sb.toString());
        return this;
    }

    @Override
    public DirectIterableImpl<T> sort(final String column) {
        sorts.add(column);
        return this;
    }

    @Override
    public DirectIterableImpl<T> sort(final String column, String direction) {
        sorts.add(column + " " + direction);
        return this;
    }

    @Override
    public DirectIterableImpl<T> limit(final String limit) {
        if (this.limit == null) {
            this.limit = limit;
            return this;
        }
        throw new IllegalStateException("Limit already specified");
    }

    @SuppressWarnings("unchecked")
    @Override
    public T newInstance() throws DirectException {
        try {
            final AbstractDirectObject newInstance = Direct.newInstance(clazz);
            newInstance.populateJoinFields(parent);
            return (T)newInstance;
        } catch (Exception e) {
            throw new DirectException(e);
        }
    }
}
