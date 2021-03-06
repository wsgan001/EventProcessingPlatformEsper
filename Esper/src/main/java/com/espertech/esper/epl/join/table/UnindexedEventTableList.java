/**************************************************************************************
 * Copyright (C) 2008 EsperTech, Inc. All rights reserved.                            *
 * http://esper.codehaus.org                                                          *
 * http://www.espertech.com                                                           *
 * ---------------------------------------------------------------------------------- *
 * The software in this package is published under the terms of the GPL license       *
 * a copy of which has been included with this distribution in the license.txt file.  *
 **************************************************************************************/
package com.espertech.esper.epl.join.table;

import com.espertech.esper.client.EventBean;
import com.espertech.esper.util.CollectionUtil;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Simple table of events without an index, based on a List implementation rather then a set
 * since we know there cannot be duplicates (such as a poll returning individual rows).
 */
public class UnindexedEventTableList implements EventTable
{
    private List<EventBean> eventSet;

    /**
     * Ctor.
     * @param eventSet is a list initializing the table
     */
    public UnindexedEventTableList(List<EventBean> eventSet)
    {
        this.eventSet = eventSet;
    }

    public void addRemove(EventBean[] newData, EventBean[] oldData) {
        add(newData);
        remove(oldData);
    }

    public void add(EventBean[] addEvents)
    {
        if (addEvents == null)
        {
            return;
        }

        eventSet.addAll(Arrays.asList(addEvents));
    }

    public void remove(EventBean[] removeEvents)
    {
        if (removeEvents == null)
        {
            return;
        }

        for (EventBean removeEvent : removeEvents)
        {
            eventSet.remove(removeEvent);
        }
    }

    public Iterator<EventBean> iterator()
    {
        if (eventSet == null)
        {
            return CollectionUtil.NULL_EVENT_ITERATOR;
        }
        return eventSet.iterator();
    }

    public boolean isEmpty()
    {
        return eventSet.isEmpty();
    }

    public String toString()
    {
        return toQueryPlan();
    }

    public String toQueryPlan() {
        return this.getClass().getSimpleName();
    }

    public void clear()
    {
        eventSet.clear();
    }
}
