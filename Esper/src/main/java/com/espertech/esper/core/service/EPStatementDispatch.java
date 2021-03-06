/**************************************************************************************
 * Copyright (C) 2008 EsperTech, Inc. All rights reserved.                            *
 * http://esper.codehaus.org                                                          *
 * http://www.espertech.com                                                           *
 * ---------------------------------------------------------------------------------- *
 * The software in this package is published under the terms of the GPL license       *
 * a copy of which has been included with this distribution in the license.txt file.  *
 **************************************************************************************/
package com.espertech.esper.core.service;

import com.espertech.esper.epl.expression.ExprEvaluatorContext;

/**
 * Interface for statement-level dispatch.
 * <p>
 * Relevant when a statements callbacks have completed and the join processing must take place.
 */
public interface EPStatementDispatch
{
    /**
     * Execute dispatch.
     * @param exprEvaluatorContext context for expression evaluation
     */
    public void execute(ExprEvaluatorContext exprEvaluatorContext);
}
