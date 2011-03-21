#
# Copyright (C) 2011 Akiban Technologies Inc.
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License, version 3,
# as published by the Free Software Foundation.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU Affero General Public License for more details.
#
# You should have received a copy of the GNU Affero General Public License
# along with this program.  If not, see http://www.gnu.org/licenses.
#

import physicaloperator

PhysicalOperator = physicaloperator.PhysicalOperator

class Sort(PhysicalOperator):

    def __init__(self, input, rowtype, sort_key):
        PhysicalOperator.__init__(self, input)
        self._input = input
        self._rowtype = rowtype
        self._sort_key = sort_key
        self._sorted = None

    def open(self):
        self._input.open()

    def next(self):
        if self._sorted is None:
            rows = []
            row = self._input.next()
            while row:
                assert row.rowtype is self._rowtype
                rows.append(row)
                row = self._input.next()
            rows.sort(key = lambda row: self._sort_key(row))
            self._sorted = iter(rows)
            self.count_sort(len(rows))
        try:
            output_row = self._sorted.next()
        except StopIteration:
            output_row = None
        return output_row

    def close(self):
        self._input.close()

    def stats(self):
        return self._stats.merge(self._input.stats())