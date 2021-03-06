/*
 * *************************************************************************************
 *  Copyright (C) 2008 EsperTech, Inc. All rights reserved.                            *
 *  http://esper.codehaus.org                                                          *
 *  http://www.espertech.com                                                           *
 *  ---------------------------------------------------------------------------------- *
 *  The software in this package is published under the terms of the GPL license       *
 *  a copy of which has been included with this distribution in the license.txt file.  *
 * *************************************************************************************
 */

package com.espertech.esper.epl.spec;

import com.espertech.esper.epl.expression.ExprNode;
import com.espertech.esper.util.MetaDefItem;

import java.io.Serializable;

/**
 * Atom in a specification for property evaluation.
 */
public class PropertyEvalAtom implements MetaDefItem, Serializable
{
    private final ExprNode splitterExpression;
    private final String optionalResultEventType;
    private final String optionalAsName;
    private final SelectClauseSpecRaw optionalSelectClause;
    private final ExprNode optionalWhereClause;
    private static final long serialVersionUID = -7123359550634592847L;

    /**
     * Ctor.
     * @param optionalAsName column name assigned, if any
     * @param optionalSelectClause select clause, if any
     * @param optionalWhereClause where clause, if any
     */
    public PropertyEvalAtom(ExprNode splitterExpression, String optionalResultEventType, String optionalAsName, SelectClauseSpecRaw optionalSelectClause, ExprNode optionalWhereClause)
    {
        this.splitterExpression = splitterExpression;
        this.optionalResultEventType = optionalResultEventType;
        this.optionalAsName = optionalAsName;
        this.optionalSelectClause = optionalSelectClause;
        this.optionalWhereClause = optionalWhereClause;
    }

    /**
     * Returns the column name if assigned.
     * @return column name
     */
    public String getOptionalAsName()
    {
        return optionalAsName;
    }

    /**
     * Returns the select clause if specified.
     * @return select clause
     */
    public SelectClauseSpecRaw getOptionalSelectClause()
    {
        return optionalSelectClause;
    }

    /**
     * Returns the where clause, if specified.
     * @return filter expression
     */
    public ExprNode getOptionalWhereClause()
    {
        return optionalWhereClause;
    }

    public ExprNode getSplitterExpression() {
        return splitterExpression;
    }

    public String getOptionalResultEventType() {
        return optionalResultEventType;
    }
}
