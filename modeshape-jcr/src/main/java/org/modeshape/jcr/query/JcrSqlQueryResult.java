/*
 * ModeShape (http://www.modeshape.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.modeshape.jcr.query;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import javax.jcr.PropertyType;
import javax.jcr.query.RowIterator;
import org.modeshape.jcr.query.validate.Schemata;

/**
 * 
 */
public class JcrSqlQueryResult extends JcrQueryResult {

    public static final String JCR_SCORE_COLUMN_NAME = "jcr:score";
    public static final String JCR_PATH_COLUMN_NAME = "jcr:path";
    /* The TypeFactory.getTypeName() always returns an uppercased type */
    public static final String JCR_SCORE_COLUMN_TYPE = PropertyType.nameFromValue(PropertyType.DOUBLE).toUpperCase();
    public static final String JCR_PATH_COLUMN_TYPE = PropertyType.nameFromValue(PropertyType.STRING).toUpperCase();

    private final List<String> columnNames;
    private final List<String> columnTypes;

    public JcrSqlQueryResult( JcrQueryContext context,
                              String query,
                              QueryResults graphResults,
                              Schemata schemata ) {
        super(context, query, graphResults, schemata);
        List<String> columnNames = new LinkedList<String>(graphResults.getColumns().getColumnNames());
        List<String> columnTypes = new LinkedList<String>(graphResults.getColumns().getColumnTypes());
        if (!columnNames.contains(JCR_SCORE_COLUMN_NAME)) {
            columnNames.add(0, JCR_SCORE_COLUMN_NAME);
            columnTypes.add(0, JCR_SCORE_COLUMN_TYPE);
        }
        if (!columnNames.contains(JCR_PATH_COLUMN_NAME)) {
            columnNames.add(0, JCR_PATH_COLUMN_NAME);
            columnTypes.add(0, JCR_PATH_COLUMN_TYPE);
        }
        this.columnNames = Collections.unmodifiableList(columnNames);
        this.columnTypes = Collections.unmodifiableList(columnTypes);
    }

    @Override
    public List<String> getColumnNameList() {
        return columnNames;
    }

    @Override
    public java.util.List<String> getColumnTypeList() {
        return columnTypes;
    }

    @Override
    public RowIterator getRows() {
        final int numRows = results.getRowCount();
        final List<Object[]> tuples = results.getTuples();
        return new SingleSelectorQueryResultRowIterator(context, queryStatement, results, tuples.iterator(), numRows);
    }
}
