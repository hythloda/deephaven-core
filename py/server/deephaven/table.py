#
#   Copyright (c) 2016-2021 Deephaven Data Labs and Patent Pending
#
""" This module implements the Table class and functions that work with Tables. """
from __future__ import annotations

from enum import Enum, auto
from typing import Union, Sequence, List

import jpy

from deephaven import DHError, dtypes
from deephaven._wrapper import JObjectWrapper
from deephaven.agg import Aggregation
from deephaven.column import Column, ColumnType
from deephaven.filters import Filter
from deephaven.jcompat import j_array_list, to_sequence

_JTableTools = jpy.get_type("io.deephaven.engine.util.TableTools")
_JColumnName = jpy.get_type("io.deephaven.api.ColumnName")
_JSortColumn = jpy.get_type("io.deephaven.api.SortColumn")
_JFilter = jpy.get_type("io.deephaven.api.filter.Filter")
_JFilterOr = jpy.get_type("io.deephaven.api.filter.FilterOr")
_JAsOfMatchRule = jpy.get_type("io.deephaven.engine.table.Table$AsOfMatchRule")
_JPair = jpy.get_type("io.deephaven.api.agg.Pair")
_JMatchPair = jpy.get_type("io.deephaven.engine.table.MatchPair")
_JLayoutHintBuilder = jpy.get_type("io.deephaven.engine.util.LayoutHintBuilder")


class SortDirection(Enum):
    """An enum defining the sorting orders."""
    DESCENDING = auto()
    """"""
    ASCENDING = auto()
    """"""


class AsOfMatchRule(Enum):
    """An enum defining matching rules on the final column to match by in as-of join and reverse as-of join
    operation. """
    LESS_THAN_EQUAL = _JAsOfMatchRule.LESS_THAN_EQUAL
    LESS_THAN = _JAsOfMatchRule.LESS_THAN
    GREATER_THAN_EQUAL = _JAsOfMatchRule.GREATER_THAN_EQUAL
    GREATER_THAN = _JAsOfMatchRule.GREATER_THAN


class Table(JObjectWrapper):
    """A Table represents a Deephaven table. It allows applications to perform powerful Deephaven table operations.

    Note: It should not be instantiated directly by user code. Tables are mostly created by factory methods,
    data ingestion operations, queries, aggregations, joins, etc.

    """

    j_object_type = jpy.get_type("io.deephaven.engine.table.Table")

    def __init__(self, j_table: jpy.JType):
        self.j_table = j_table
        self._definition = self.j_table.getDefinition()
        self._schema = None

    def __repr__(self):
        default_repr = super().__repr__()
        column_dict = {col.name: col.data_type for col in self.columns[:10]}
        repr_str = (
            f"{default_repr[:-2]}, num_rows = {self.size}, columns = {column_dict}"
        )
        repr_str = repr_str[:115] + "...}>" if len(repr_str) > 120 else repr_str
        return repr_str

    def __str__(self):
        return repr(self)

    @property
    def size(self) -> int:
        """The current number of rows in the table."""
        return self.j_table.size()

    @property
    def is_refreshing(self) -> bool:
        """Whether this table is refreshing."""
        return self.j_table.isRefreshing()

    @property
    def columns(self) -> List[Column]:
        """The column definitions of the table."""
        if self._schema:
            return self._schema

        self._schema = []
        j_col_list = self._definition.getColumnList()
        for i in range(j_col_list.size()):
            j_col = j_col_list.get(i)
            self._schema.append(
                Column(
                    name=j_col.getName(),
                    data_type=dtypes.from_jtype(j_col.getDataType()),
                    component_type=dtypes.from_jtype(j_col.getComponentType()),
                    column_type=ColumnType(j_col.getColumnType()),
                )
            )
        return self._schema

    @property
    def meta_table(self) -> Table:
        """The column definitions of the table in a Table form. """
        return Table(j_table=self.j_table.getMeta())

    @property
    def j_object(self) -> jpy.JType:
        return self.j_table

    def to_string(self, num_rows: int = 10, cols: Union[str, Sequence[str]] = None) -> str:
        """Returns the first few rows of a table as a pipe-delimited string.

        Args:
            num_rows (int): the number of rows at the beginning of the table
            cols (Union[str, Sequence[str]]): the column name(s), default is None

        Returns:
            string

        Raises:
            DHError
        """
        try:
            cols = to_sequence(cols)
            return _JTableTools.string(self.j_table, num_rows, *cols)
        except Exception as e:
            raise DHError(e, "table to_string failed") from e

    def coalesce(self) -> Table:
        """Returns a coalesced child table."""
        return Table(j_table=self.j_table.coalesce())

    def snapshot(self, source_table: Table, do_init: bool = False, cols: Union[str, List[str]] = None) -> Table:
        """Produces an in-memory copy of a source table that refreshes when this table changes.

        Note, this table is often a time table that adds new rows at a regular, user-defined interval.

        Args:
            source_table (Table): the table to be snapshot
            do_init (bool): whether to snapshot when this method is initially called, default is False
            cols (Union[str, List[str]]): names of the columns of this table to be included in the snapshot, default is
                None, meaning all the columns

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            cols = to_sequence(cols)
            return Table(j_table=self.j_table.snapshot(source_table.j_table, do_init, *cols))
        except Exception as e:
            raise DHError(message="failed to create a snapshot table.") from e

    def snapshot_history(self, source_table: Table) -> Table:
        """Produces an in-memory history of a source table that adds a new snapshot when this table (trigger table)
        changes.

        The trigger table is often a time table that adds new rows at a regular, user-defined interval.

        Columns from the trigger table appear in the result table. If the trigger and source tables have columns with
        the same name, an error will be raised. To avoid this problem, rename conflicting columns.

        Because snapshot_history stores a copy of the source table for every trigger event, large source tables or
        rapidly changing trigger tables can result in large memory usage.

        Args:
            source_table (Table): the table to be snapshot

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            return Table(j_table=self.j_table.snapshotHistory(source_table.j_table))
        except Exception as e:
            raise DHError(message="failed to create a snapshot history table.") from e

    #
    # Table operation category: Select
    #
    # region Select
    def drop_columns(self, cols: Union[str, Sequence[str]]) -> Table:
        """The drop_columns method creates a new table with the same size as this table but omits any of specified
        columns.

        Args:
            cols (Union[str, Sequence[str]): the column name(s)

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            cols = to_sequence(cols)
            return Table(j_table=self.j_table.dropColumns(*cols))
        except Exception as e:
            raise DHError(e, "table drop_columns operation failed.") from e

    def move_columns(self, idx: int, cols: Union[str, Sequence[str]]) -> Table:
        """The move_columns method creates a new table with specified columns moved to a specific column index value.

        Args:
            idx (int): the column index where the specified columns will be moved in the new table.
            cols (Union[str, Sequence[str]]) : the column name(s)

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            cols = to_sequence(cols)
            return Table(j_table=self.j_table.moveColumns(idx, *cols))
        except Exception as e:
            raise DHError(e, "table move_columns operation failed.") from e

    def move_columns_down(self, cols: Union[str, Sequence[str]]) -> Table:
        """The move_columns_down method creates a new table with specified columns appearing last in order, to the far
        right.

        Args:
            cols (Union[str, Sequence[str]]) : the column name(s)

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            cols = to_sequence(cols)
            return Table(j_table=self.j_table.moveColumnsDown(*cols))
        except Exception as e:
            raise DHError(e, "table move_columns_down operation failed.") from e

    def move_columns_up(self, cols: Union[str, Sequence[str]]) -> Table:
        """The move_columns_up method creates a new table with specified columns appearing first in order, to the far
        left.

        Args:
            cols (Union[str, Sequence[str]]) : the column name(s)

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            cols = to_sequence(cols)
            return Table(j_table=self.j_table.moveColumnsUp(*cols))
        except Exception as e:
            raise DHError(e, "table move_columns_up operation failed.") from e

    def rename_columns(self, cols: Union[str, Sequence[str]]) -> Table:
        """The rename_columns method creates a new table with the specified columns renamed.

        Args:
            cols (Union[str, Sequence[str]]) : the column rename expr(s) as "X = Y"

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            cols = to_sequence(cols)
            return Table(j_table=self.j_table.renameColumns(*cols))
        except Exception as e:
            raise DHError(e, "table rename_columns operation failed.") from e

    def update(self, formulas: Union[str, Sequence[str]]) -> Table:
        """The update method creates a new table containing a new, in-memory column for each formula.

        Args:
            formulas (Union[str, Sequence[str]]): the column formula(s)

        Returns:
            A new table

        Raises:
            DHError
        """
        try:
            formulas = to_sequence(formulas)
            return Table(j_table=self.j_table.update(*formulas))
        except Exception as e:
            raise DHError(e, "table update operation failed.") from e

    def lazy_update(self, formulas: Union[str, Sequence[str]]) -> Table:
        """The lazy_update method creates a new table containing a new, cached, formula column for each formula.

        Args:
            formulas (Union[str, Sequence[str]]): the column formula(s)

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            formulas = to_sequence(formulas)
            return Table(j_table=self.j_table.lazyUpdate(*formulas))
        except Exception as e:
            raise DHError(e, "table lazy_update operation failed.") from e

    def view(self, formulas: Union[str, Sequence[str]]) -> Table:
        """The view method creates a new formula table that includes one column for each formula.

        Args:
            formulas (Union[str, Sequence[str]]): the column formula(s)

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            formulas = to_sequence(formulas)
            return Table(j_table=self.j_table.view(*formulas))
        except Exception as e:
            raise DHError(e, "table view operation failed.") from e

    def update_view(self, formulas: Union[str, Sequence[str]]) -> Table:
        """The update_view method creates a new table containing a new, formula column for each formula.

        Args:
            formulas (Union[str, Sequence[str]]): the column formula(s)

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            formulas = to_sequence(formulas)
            return Table(j_table=self.j_table.updateView(*formulas))
        except Exception as e:
            raise DHError(e, "table update_view operation failed.") from e

    def select(self, formulas: Union[str, Sequence[str]] = None) -> Table:
        """The select method creates a new in-memory table that includes one column for each formula. If no formula
        is specified, all columns will be included.

        Args:
            formulas (Union[str, Sequence[str]], optional): the column formula(s), default is None

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            if not formulas:
                return Table(j_table=self.j_table.select())
            formulas = to_sequence(formulas)
            return Table(j_table=self.j_table.select(*formulas))
        except Exception as e:
            raise DHError(e, "table select operation failed.") from e

    def select_distinct(self, formulas: Union[str, Sequence[str]] = None) -> Table:
        """The select_distinct method creates a new table containing all of the unique values for a set of key
        columns. When the selectDistinct method is used on multiple columns, it looks for distinct sets of values in
        the selected columns.

        Args:
            formulas (Union[str, Sequence[str]], optional): the column name(s), default is None

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            formulas = to_sequence(formulas)
            return Table(j_table=self.j_table.selectDistinct(*formulas))
        except Exception as e:
            raise DHError(e, "table select_distinct operation failed.") from e

    # endregion

    #
    # Table operation category: Filter
    #
    # region Filter

    def where(self, filters: Union[str, Filter, Sequence[str], Sequence[Filter]] = None) -> Table:
        """The where method creates a new table with only the rows meeting the filter criteria in the column(s) of
        the table.

        Args:
            filters (Union[str, Filter, Sequence[str], Sequence[Filter]], optional): the filter condition
                expression(s) or Filter object(s), default is None

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            filters = to_sequence(filters)
            return Table(j_table=self.j_table.where(*filters))
        except Exception as e:
            raise DHError(e, "table where operation failed.") from e

    def where_in(self, filter_table: Table, cols: Union[str, Sequence[str]]) -> Table:
        """The where_in method creates a new table containing rows from the source table, where the rows match
        values in the filter table. The filter is updated whenever either table changes.

        Args:
            filter_table (Table): the table containing the set of values to filter on
            cols (Union[str, Sequence[str]]): the column name(s)

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            cols = to_sequence(cols)
            return Table(j_table=self.j_table.whereIn(filter_table.j_table, *cols))
        except Exception as e:
            raise DHError(e, "table where_in operation failed.") from e

    def where_not_in(self, filter_table: Table, cols: Union[str, Sequence[str]]) -> Table:
        """The where_not_in method creates a new table containing rows from the source table, where the rows do not
        match values in the filter table.

        Args:
            filter_table (Table): the table containing the set of values to filter on
            cols (Union[str, Sequence[str]]): the column name(s)

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            cols = to_sequence(cols)
            return Table(j_table=self.j_table.whereNotIn(filter_table.j_table, *cols))
        except Exception as e:
            raise DHError(e, "table where_not_in operation failed.") from e

    def where_one_of(self, filters: Union[str, Sequence[str]] = None) -> Table:
        """The where_one_of method creates a new table containing rows from the source table, where the rows match at
        least one filter.

        Args:
            filters (Union[str, Sequence[str]], optional): the filter condition expression(s), default is None

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            filters = to_sequence(filters)
            return Table(
                j_table=self.j_table.where(_JFilterOr.of(_JFilter.from_(*filters)))
            )
        except Exception as e:
            raise DHError(e, "table where_one_of operation failed.") from e

    def head(self, num_rows: int) -> Table:
        """The head method creates a new table with a specific number of rows from the beginning of the table.

        Args:
            num_rows (int): the number of rows at the head of table

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            return Table(j_table=self.j_table.head(num_rows))
        except Exception as e:
            raise DHError(e, "table head operation failed.") from e

    def head_pct(self, pct: float) -> Table:
        """The head_pct method creates a new table with a specific percentage of rows from the beginning of the table.

        Args:
            pct (float): the percentage of rows to return as a value from 0 (0%) to 1 (100%).

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            return Table(j_table=self.j_table.headPct(pct))
        except Exception as e:
            raise DHError(e, "table head_pct operation failed.") from e

    def tail(self, num_rows: int) -> Table:
        """The tail method creates a new table with a specific number of rows from the end of the table.

        Args:
            num_rows (int): the number of rows at the end of table

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            return Table(j_table=self.j_table.tail(num_rows))
        except Exception as e:
            raise DHError(e, "table tail operation failed.") from e

    def tail_pct(self, pct: float) -> Table:
        """The tail_pct method creates a new table with a specific percentage of rows from the end of the table.

        Args:
            pct (float): the percentage of rows to return as a value from 0 (0%) to 1 (100%).

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            return Table(j_table=self.j_table.tailPct(pct))
        except Exception as e:
            raise DHError(e, "table tail_pct operation failed.") from e

    # endregion

    #
    # Table operation category: Sort
    #
    # region Sort
    def restrict_sort_to(self, cols: Union[str, Sequence[str]]):
        """The restrict_sort_to method only allows sorting on specified table columns. This can be useful to prevent
        users from accidentally performing expensive sort operations as they interact with tables in the UI.

        Args:
            cols (Union[str, Sequence[str]]): the column name(s)

        Raises:
            DHError
        """
        try:
            cols = to_sequence(cols)
            return self.j_table.restrictSortTo(*cols)
        except Exception as e:
            raise DHError(e, "table restrict_sort_to operation failed.") from e

    def sort_descending(self, order_by: Union[str, Sequence[str]]) -> Table:
        """The sort_descending method creates a new table where rows in a table are sorted in a largest to smallest
        order based on the order_by column(s).

        Args:
            order_by (Union[str, Sequence[str]], optional): the column name(s)

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            order_by = to_sequence(order_by)
            return Table(j_table=self.j_table.sortDescending(*order_by))
        except Exception as e:
            raise DHError(e, "table sort_descending operation failed.") from e

    def reverse(self) -> Table:
        """The reverse method creates a new table with all of the rows from this table in reverse order.

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            return Table(j_table=self.j_table.reverse())
        except Exception as e:
            raise DHError(e, "table reverse operation failed.") from e

    def sort(self, order_by: Union[str, Sequence[str]],
             order: Union[SortDirection, Sequence[SortDirection]] = None) -> Table:
        """The sort method creates a new table where (1) rows are sorted in a smallest to largest order based on the
        order_by column(s) (2) where rows are sorted in the order defined by the order argument.

        Args:
            order_by (Union[str, Sequence[str]]): the column(s) to be sorted on
            order (Union[SortDirection, Sequence[SortDirection], optional): the corresponding sort directions for each sort
                column, default is None. In the absence of explicit sort directions, data will be sorted in the ascending order.

        Returns:
            a new table

        Raises:
            DHError
        """

        def sort_column(col, dir_):
            return (
                _JSortColumn.desc(_JColumnName.of(col))
                if dir_ == SortDirection.DESCENDING
                else _JSortColumn.asc(_JColumnName.of(col))
            )

        try:
            order_by = to_sequence(order_by)
            order = to_sequence(order)
            if order:
                sort_columns = [
                    sort_column(col, dir_) for col, dir_ in zip(order_by, order)
                ]
                j_sc_list = j_array_list(sort_columns)
                return Table(j_table=self.j_table.sort(j_sc_list))
            else:
                return Table(j_table=self.j_table.sort(*order_by))
        except Exception as e:
            raise DHError(e, "table sort operation failed.") from e

    # endregion

    #
    # Table operation category: Join
    #
    # region Join
    def natural_join(self, table: Table, on: Union[str, Sequence[str]],
                     joins: Union[str, Sequence[str]] = None) -> Table:
        """The natural_join method creates a new table containing all of the rows and columns of this table,
        plus additional columns containing data from the right table. For columns appended to the left table (joins),
        row values equal the row values from the right table where the key values in the left and right tables are
        equal. If there is no matching key in the right table, appended row values are NULL.

        Args:
            table (Table): the right-table of the join
            on (Union[str, Sequence[str]]): the column(s) to match, can be a common name or an equal expression,
                i.e. "col_a = col_b" for different column names
            joins (Union[str, Sequence[str]], optional): the column(s) to be added from the right table to the result
                table, can be renaming expressions, i.e. "new_col = col"; default is None

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            on = to_sequence(on)
            joins = to_sequence(joins)
            if joins:
                return Table(
                    j_table=self.j_table.naturalJoin(
                        table.j_table, ",".join(on), ",".join(joins)
                    )
                )
            else:
                return Table(
                    j_table=self.j_table.naturalJoin(table.j_table, ",".join(on))
                )
        except Exception as e:
            raise DHError(e, "table natural_join operation failed.") from e

    def exact_join(self, table: Table, on: Union[str, Sequence[str]], joins: Union[str, Sequence[str]] = None) -> Table:
        """The exact_join method creates a new table containing all of the rows and columns of this table plus
        additional columns containing data from the right table. For columns appended to the left table (joins),
        row values equal the row values from the right table where the key values in the left and right tables are
        equal.

        Args:
            table (Table): the right-table of the join
            on (Union[str, Sequence[str]]): the column(s) to match, can be a common name or an equal expression,
                i.e. "col_a = col_b" for different column names
            joins (Union[str, Sequence[str]], optional): the column(s) to be added from the right table to the result
                table, can be renaming expressions, i.e. "new_col = col"; default is None

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            on = to_sequence(on)
            joins = to_sequence(joins)
            if joins:
                return Table(
                    j_table=self.j_table.exactJoin(
                        table.j_table, ",".join(on), ",".join(joins)
                    )
                )
            else:
                return Table(
                    j_table=self.j_table.exactJoin(table.j_table, ",".join(on))
                )
        except Exception as e:
            raise DHError(e, "table exact_join operation failed.") from e

    def join(self, table: Table, on: Union[str, Sequence[str]] = None, joins: Union[str, Sequence[str]] = None) -> Table:
        """The join method creates a new table containing rows that have matching values in both tables. Rows that
        do not have matching criteria will not be included in the result. If there are multiple matches between a row
        from the left table and rows from the right table, all matching combinations will be included. If no columns
        to match (on) are specified, every combination of left and right table rows is included.

        Args:
            table (Table): the right-table of the join
            on (Union[str, Sequence[str]]): the column(s) to match, can be a common name or an equal expression,
                i.e. "col_a = col_b" for different column names; default is None
            joins (Union[str, Sequence[str]], optional): the column(s) to be added from the right table to the result
                table, can be renaming expressions, i.e. "new_col = col"; default is None

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            on = to_sequence(on)
            joins = to_sequence(joins)
            if joins:
                return Table(
                    j_table=self.j_table.join(
                        table.j_table, ",".join(on), ",".join(joins)
                    )
                )
            else:
                return Table(j_table=self.j_table.join(table.j_table, ",".join(on)))
        except Exception as e:
            raise DHError(e, "table join operation failed.") from e

    def aj(self, table: Table, on: Union[str, Sequence[str]], joins: Union[str, Sequence[str]] = None,
           match_rule: AsOfMatchRule = AsOfMatchRule.LESS_THAN_EQUAL) -> Table:
        """The aj (as-of join) method creates a new table containing all of the rows and columns of the left table,
        plus additional columns containing data from the right table. For columns appended to the left table (joins),
        row values equal the row values from the right table where the keys from the left table most closely match
        the keys from the right table without going over. If there is no matching key in the right table, appended row
        values are NULL.

        Args:
            table (Table): the right-table of the join
            on (Union[str, Sequence[str]]): the column(s) to match, can be a common name or an equal expression,
                i.e. "col_a = col_b" for different column names
            joins (Union[str, Sequence[str]], optional): the column(s) to be added from the right table to the result
                table, can be renaming expressions, i.e. "new_col = col"; default is None
            match_rule (AsOfMatchRule): the inexact matching rule on the last column to match specified in 'on',
                default is AsOfMatchRule.LESS_THAN_EQUAL. The other valid value is AsOfMatchRule.LESS_THAN.
        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            on = to_sequence(on)
            joins = to_sequence(joins)
            if on:
                on = [_JMatchPair.of(_JPair.parse(p)) for p in on]
            if joins:
                joins = [_JMatchPair.of(_JPair.parse(p)) for p in joins]
            return Table(j_table=self.j_table.aj(table.j_table, on, joins, match_rule.value))
        except Exception as e:
            raise DHError(e, "table as-of join operation failed.") from e

    def raj(self, table: Table, on: Union[str, Sequence[str]], joins: Union[str, Sequence[str]] = None,
            match_rule: AsOfMatchRule = AsOfMatchRule.GREATER_THAN_EQUAL) -> Table:
        """The reverse-as-of join method creates a new table containing all of the rows and columns of the left table,
        plus additional columns containing data from the right table. For columns appended to the left table (joins),
        row values equal the row values from the right table where the keys from the left table most closely match
        the keys from the right table without going under. If there is no matching key in the right table, appended row
        values are NULL.

        Args:
            table (Table): the right-table of the join
            on (Union[str, Sequence[str]]): the column(s) to match, can be a common name or an equal expression,
                i.e. "col_a = col_b" for different column names
            joins (Union[str, Sequence[str]], optional): the column(s) to be added from the right table to the result
                table, can be renaming expressions, i.e. "new_col = col"; default is None
            match_rule (AsOfMatchRule): the inexact matching rule on the last column to match specified in 'on',
                default is AsOfMatchRule.GREATER_THAN_EQUAL. The other valid value is AsOfMatchRule.GREATER_THAN.

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            on = to_sequence(on)
            joins = to_sequence(joins)
            on = to_sequence(on)
            joins = to_sequence(joins)
            if on:
                on = [_JMatchPair.of(_JPair.parse(p)) for p in on]
            if joins:
                joins = [_JMatchPair.of(_JPair.parse(p)) for p in joins]
            return Table(j_table=self.j_table.raj(table.j_table, on, joins, match_rule.value))
        except Exception as e:
            raise DHError(e, "table reverse-as-of join operation failed.") from e

    # endregion

    #
    # Table operation category: Aggregation
    # region Aggregation
    def head_by(self, num_rows: int, by: Union[str, Sequence[str]] = None) -> Table:
        """The head_by method creates a new table containing the first number of rows for each group.

        Args:
            num_rows (int): the number of rows at the beginning of each group
            by (Union[str, Sequence[str]]): the group-by column name(s), default is None

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            by = to_sequence(by)
            return Table(j_table=self.j_table.headBy(num_rows, *by))
        except Exception as e:
            raise DHError(e, "table head_by operation failed.") from e

    def tail_by(self, num_rows: int, by: Union[str, Sequence[str]] = None) -> Table:
        """The tail_by method creates a new table containing the last number of rows for each group.

        Args:
            num_rows (int): the number of rows at the end of each group
            by (Union[str, Sequence[str]]): the group-by column name(s), default is None

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            by = to_sequence(by)
            return Table(j_table=self.j_table.tailBy(num_rows, *by))
        except Exception as e:
            raise DHError(e, "table tail_by operation failed.") from e

    def group_by(self, by: Union[str, Sequence[str]] = None) -> Table:
        """The group_by method creates a new table containing grouping columns and grouped data, column content is
        grouped into arrays.

        Args:
            by (Union[str, Sequence[str]], optional): the group-by column name(s), default is None

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            by = to_sequence(by)
            if by:
                return Table(j_table=self.j_table.groupBy(*by))
            else:
                return Table(j_table=self.j_table.groupBy())
        except Exception as e:
            raise DHError(e, "table group operation failed.") from e

    def ungroup(self, cols: Union[str, Sequence[str]] = None) -> Table:
        """The ungroup method creates a new table in which array columns from the source table are unwrapped into
        separate rows.

        Args:
            cols (Union[str, Sequence[str]], optional): the name(s) of the array column(s), if None, all array columns 
                will be ungrouped, default is None

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            cols = to_sequence(cols)
            if cols:
                return Table(j_table=self.j_table.ungroup(*cols))
            else:
                return Table(j_table=self.j_table.ungroup())
        except Exception as e:
            raise DHError(e, "table ungroup operation failed.") from e

    def first_by(self, by: Union[str, Sequence[str]] = None) -> Table:
        """The first_by method creates a new table containing the first row for each group.

        Args:
            by (Union[str, Sequence[str]], optional): the group-by column name(s), default is None

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            by = to_sequence(by)
            if by:
                return Table(j_table=self.j_table.firstBy(*by))
            else:
                return Table(j_table=self.j_table.firstBy())
        except Exception as e:
            raise DHError(e, "table first_by operation failed.") from e

    def last_by(self, by: Union[str, Sequence[str]] = None) -> Table:
        """The last_by method creates a new table containing the last row for each group.

        Args:
            by (Union[str, Sequence[str]], optional): the group-by column name(s), default is None

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            by = to_sequence(by)
            if by:
                return Table(j_table=self.j_table.lastBy(*by))
            else:
                return Table(j_table=self.j_table.lastBy())
        except Exception as e:
            raise DHError(e, "table last_by operation failed.") from e

    def sum_by(self, by: Union[str, Sequence[str]] = None) -> Table:
        """The sum_by method creates a new table containing the sum for each group.

        Args:
            by (Union[str, Sequence[str]], optional): the group-by column name(s), default is None

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            by = to_sequence(by)
            if by:
                return Table(j_table=self.j_table.sumBy(*by))
            else:
                return Table(j_table=self.j_table.sumBy())
        except Exception as e:
            raise DHError(e, "table sum_by operation failed.") from e

    def avg_by(self, by: Union[str, Sequence[str]] = None) -> Table:
        """The avg_by method creates a new table containing the average for each group.

        Args:
            by (Union[str, Sequence[str]], optional): the group-by column name(s), default is None

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            by = to_sequence(by)
            if by:
                return Table(j_table=self.j_table.avgBy(*by))
            else:
                return Table(j_table=self.j_table.avgBy())
        except Exception as e:
            raise DHError(e, "table avg_by operation failed.") from e

    def std_by(self, by: Union[str, Sequence[str]] = None) -> Table:
        """The std_by method creates a new table containing the standard deviation for each group.

        Args:
            by (Union[str, Sequence[str]], optional): the group-by column name(s), default is None

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            by = to_sequence(by)
            if by:
                return Table(j_table=self.j_table.stdBy(*by))
            else:
                return Table(j_table=self.j_table.stdBy())
        except Exception as e:
            raise DHError(e, "table std_by operation failed.") from e

    def var_by(self, by: Union[str, Sequence[str]] = None) -> Table:
        """The var_by method creates a new table containing the variance for each group.

        Args:
            by (Union[str, Sequence[str]], optional): the group-by column name(s), default is None

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            by = to_sequence(by)
            if by:
                return Table(j_table=self.j_table.varBy(*by))
            else:
                return Table(j_table=self.j_table.varBy())
        except Exception as e:
            raise DHError(e, "table var_by operation failed.") from e

    def median_by(self, by: Union[str, Sequence[str]] = None) -> Table:
        """The median_by method creates a new table containing the median for each group.

        Args:
            by (Union[str, Sequence[str]], optional): the group-by column name(s), default is None

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            by = to_sequence(by)
            if by:
                return Table(j_table=self.j_table.medianBy(*by))
            else:
                return Table(j_table=self.j_table.medianBy())
        except Exception as e:
            raise DHError(e, "table median_by operation failed.") from e

    def min_by(self, by: Union[str, Sequence[str]] = None) -> Table:
        """The min_by method creates a new table containing the minimum value for each group.

        Args:
            by (Union[str, Sequence[str]], optional): the group-by column name(s), default is None

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            by = to_sequence(by)
            if by:
                return Table(j_table=self.j_table.minBy(*by))
            else:
                return Table(j_table=self.j_table.minBy())
        except Exception as e:
            raise DHError(e, "table min_by operation failed.") from e

    def max_by(self, by: Union[str, Sequence[str]] = None) -> Table:
        """The max_by method creates a new table containing the maximum value for each group.

        Args:
            by (Union[str, Sequence[str]], optional): the group-by column name(s), default is None

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            by = to_sequence(by)
            if by:
                return Table(j_table=self.j_table.maxBy(*by))
            else:
                return Table(j_table=self.j_table.maxBy())
        except Exception as e:
            raise DHError(e, "table max_by operation failed.") from e

    def count_by(self, col: str, by: Union[str, Sequence[str]] = None) -> Table:
        """The count_by method creates a new table containing the number of rows for each group.

        Args:
            col (str): the name of the column to store the counts
            by (Union[str, Sequence[str]], optional): the group-by column name(s), default is None

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            by = to_sequence(by)
            if by:
                return Table(j_table=self.j_table.countBy(col, *by))
            else:
                return Table(j_table=self.j_table.countBy(col))
        except Exception as e:
            raise DHError(e, "table count_by operation failed.") from e

    def agg_by(self, aggs: Union[Aggregation, Sequence[Aggregation]], by: Union[str, Sequence[str]] = None) -> Table:
        """The agg_by method creates a new table containing grouping columns and grouped data. The resulting
        grouped data is defined by the aggregations specified.

        Args:
            aggs (Union[Aggregation, Sequence[Aggregation]]): the aggregation(s)
            by (Union[str, Sequence[str]]): the group-by column name(s), default is None

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            aggs = to_sequence(aggs)
            by = to_sequence(by)
            j_agg_list = j_array_list([agg.j_aggregation for agg in aggs])
            return Table(j_table=self.j_table.aggBy(j_agg_list, *by))
        except Exception as e:
            raise DHError(e, "table agg_by operation failed.") from e

    def agg_all_by(self, agg: Aggregation, by: Union[str, Sequence[str]] = None) -> Table:
        """The agg_all_by method creates a new table containing grouping columns and grouped data. The resulting
        grouped data is defined by the aggregation specified.

        Note, because agg_all_by applies the aggregation to all the columns of the table, it will ignore
        any column names specified for the aggregation.

        Args:
            agg (Aggregation): the aggregation
            by (Union[str, Sequence[str]], optional): the group-by column name(s), default is None

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            by = to_sequence(by)
            return Table(j_table=self.j_table.aggAllBy(agg.j_agg_spec, *by))
        except Exception as e:
            raise DHError(e, "table agg_all_by operation failed.") from e

    # endregion

    def partition_by(self, by: Union[str, Sequence[str]]) -> jpy.JType:
        """ Creates a TableMap (opaque) by dividing this table into sub-tables.

        Args:
            by (Union[str, Sequence[str]]): the column(s) by which to group data

        Returns:
            A TableMap containing a sub-table for each group

        Raises:
            DHError
        """
        try:
            by = to_sequence(by)
            return self.j_table.partitionBy(*by)
        except Exception as e:
            raise DHError(e, "failed to create a TableMap.") from e

    def format_columns(self, formulas: Union[str, List[str]]) -> Table:
        """ Applies color formatting to the columns of the table.

        Args:
            formulas (Union[str, List[str]]): formatting string(s) in the form of "column=color_expression"
                where color_expression can be a color name or a Java ternary expression that results in a color.

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            formulas = to_sequence(formulas)
            return Table(j_table=self.j_table.formatColumns(formulas))
        except Exception as e:
            raise DHError(e, "failed to color format columns.") from e

    def format_column_where(self, col: str, cond: str, formula: str) -> Table:
        """ Applies color formatting to a column of the table conditionally.

        Args:
            col (str): the column name
            cond (str): the condition expression
            formula (str): the formatting string in the form of assignment expression "column=color expression"
                where color_expression can be a color name or a Java ternary expression that results in a color.

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            return Table(j_table=self.j_table.formatColumnWhere(col, cond, formula))
        except Exception as e:
            raise DHError(e, "failed to color format column conditionally.") from e

    def format_row_where(self, cond: str, formula: str) -> Table:
        """ Applies color formatting to rows of the table conditionally.

        Args:
            cond (str): the condition expression
            formula (str): the formatting string in the form of assignment expression "column=color expression"
                where color_expression can be a color name or a Java ternary expression that results in a color.

        Returns:
            a new table

        Raises:
            DHError
        """
        try:
            return Table(j_table=self.j_table.formatRowWhere(cond, formula))
        except Exception as e:
            raise DHError(e, "failed to color format rows conditionally.") from e

    def layout_hints(self, front: Union[str, List[str]] = None, back: Union[str, List[str]] = None,
                     freeze: Union[str, List[str]] = None, hide: Union[str, List[str]] = None) -> Table:
        """ Sets layout hints on the Table

        Args:
            front (Union[str, List[str]]): the columns to show at the front
            back (Union[str, List[str]]): the columns to show at the back
            freeze (Union[str, List[str]]): the columns to freeze to the front.
                These will not be affected by horizontal scrolling.
            hide (Union[str, List[str]]): the columns to hide

        Returns:
            a new table with the layout hints set

        Raises:
            DHError
        """
        try:
            _j_layout_hint_builder = _JLayoutHintBuilder.get()

            if front is not None:
                _j_layout_hint_builder.atFront(to_sequence(front))

            if back is not None:
                _j_layout_hint_builder.atEnd(to_sequence(back))

            if freeze is not None:
                _j_layout_hint_builder.freeze(to_sequence(freeze))

            if hide is not None:
                _j_layout_hint_builder.hide(to_sequence(hide))
        except Exception as e:
            raise DHError(e, "failed to create layout hints") from e

        try:
            return Table(j_table=self.j_table.setLayoutHints(_j_layout_hint_builder.build()))
        except Exception as e:
            raise DHError(e, "failed to set layout hints on table") from e
