/**************************************************************************************
 * Copyright (C) 2008 EsperTech, Inc. All rights reserved.                            *
 * http://esper.codehaus.org                                                          *
 * http://www.espertech.com                                                           *
 * ---------------------------------------------------------------------------------- *
 * The software in this package is published under the terms of the GPL license       *
 * a copy of which has been included with this distribution in the license.txt file.  *
 **************************************************************************************/
package com.espertech.esper.epl.core;

import com.espertech.esper.client.ConfigurationMethodRef;
import com.espertech.esper.client.ConfigurationPlugInAggregationFunction;
import com.espertech.esper.client.ConfigurationPlugInAggregationMultiFunction;
import com.espertech.esper.client.ConfigurationPlugInSingleRowFunction;
import com.espertech.esper.client.hook.AggregationFunctionFactory;
import com.espertech.esper.collection.Pair;
import com.espertech.esper.epl.agg.service.AggregationSupport;
import com.espertech.esper.epl.expression.*;
import com.espertech.esper.util.JavaClassHelper;
import com.espertech.esper.util.MethodResolver;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.MathContext;
import java.util.*;

/**
 * Implementation for engine-level imports.
 */
public class EngineImportServiceImpl implements EngineImportService
{
    private static final Log log = LogFactory.getLog(EngineImportServiceImpl.class);

	private final List<String> imports;
    private final Map<String, ConfigurationPlugInAggregationFunction> aggregationFunctions;
    private final List<Pair<Set<String>, ConfigurationPlugInAggregationMultiFunction>> aggregationAccess;
    private final Map<String, EngineImportSingleRowDesc> singleRowFunctions;
    private final Map<String, ConfigurationMethodRef> methodInvocationRef;
    private final boolean allowExtendedAggregationFunc;
    private final boolean isUdfCache;
    private final boolean isDuckType;
    private final boolean sortUsingCollator;
    private final MathContext optionalDefaultMathContext;

    /**
	 * Ctor
     * @param allowExtendedAggregationFunc true to allow non-SQL standard builtin agg functions.
	 */
	public EngineImportServiceImpl(boolean allowExtendedAggregationFunc, boolean isUdfCache, boolean isDuckType, boolean sortUsingCollator, MathContext optionalDefaultMathContext)
    {
        imports = new ArrayList<String>();
        aggregationFunctions = new HashMap<String, ConfigurationPlugInAggregationFunction>();
        aggregationAccess = new ArrayList<Pair<Set<String>, ConfigurationPlugInAggregationMultiFunction>>();
        singleRowFunctions = new HashMap<String, EngineImportSingleRowDesc>();
        methodInvocationRef = new HashMap<String, ConfigurationMethodRef>();
        this.allowExtendedAggregationFunc = allowExtendedAggregationFunc;
        this.isUdfCache = isUdfCache;
        this.isDuckType = isDuckType;
        this.sortUsingCollator = sortUsingCollator;
        this.optionalDefaultMathContext = optionalDefaultMathContext;
    }

    public boolean isUdfCache() {
        return isUdfCache;
    }

    public boolean isDuckType() {
        return isDuckType;
    }

    public ConfigurationMethodRef getConfigurationMethodRef(String className)
    {
        return methodInvocationRef.get(className);
    }

    /**
     * Adds cache configs for method invocations for from-clause.
     * @param configs cache configs
     */
    public void addMethodRefs(Map<String, ConfigurationMethodRef> configs)
    {
        methodInvocationRef.putAll(configs);
    }

    public void addImport(String importName) throws EngineImportException
    {
        if(!isClassName(importName) && !isPackageName(importName))
        {
            throw new EngineImportException("Invalid import name '" + importName + "'");
        }
        if (log.isDebugEnabled()) {
            log.debug("Adding import " + importName);
        }

        imports.add(importName);
    }

    public void addAggregation(String functionName, ConfigurationPlugInAggregationFunction aggregationDesc) throws EngineImportException
    {
        validateFunctionName("aggregation function", functionName);

        if (aggregationDesc.getFactoryClassName() != null) {
            if(!isClassName(aggregationDesc.getFactoryClassName()))
            {
                throw new EngineImportException("Invalid class name for aggregation factory '" + aggregationDesc.getFactoryClassName() + "'");
            }
        }
        else {
            if(!isClassName(aggregationDesc.getFunctionClassName()))
            {
                throw new EngineImportException("Invalid class name for aggregation function '" + aggregationDesc.getFunctionClassName() + "'");
            }
        }
        aggregationFunctions.put(functionName.toLowerCase(), aggregationDesc);
    }

    public void addSingleRow(String functionName, String singleRowFuncClass, String methodName, ConfigurationPlugInSingleRowFunction.ValueCache valueCache, ConfigurationPlugInSingleRowFunction.FilterOptimizable filterOptimizable, boolean rethrowExceptions) throws EngineImportException {
        validateFunctionName("single-row", functionName);

        if(!isClassName(singleRowFuncClass))
        {
            throw new EngineImportException("Invalid class name for aggregation '" + singleRowFuncClass + "'");
        }
        singleRowFunctions.put(functionName.toLowerCase(), new EngineImportSingleRowDesc(singleRowFuncClass, methodName, valueCache, filterOptimizable, rethrowExceptions));
    }

    public AggregationSupport resolveAggregation(String name) throws EngineImportException, EngineImportUndefinedException
    {
        ConfigurationPlugInAggregationFunction desc = aggregationFunctions.get(name);
        if (desc == null)
        {
            desc = aggregationFunctions.get(name.toLowerCase());
        }
        if (desc == null || desc.getFunctionClassName() == null)
        {
            throw new EngineImportUndefinedException("A function named '" + name + "' is not defined");
        }

        String className = desc.getFunctionClassName();
        Class clazz;
        try
        {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            clazz = Class.forName(className, true, cl);
        }
        catch (ClassNotFoundException ex)
        {
            throw new EngineImportException("Could not load aggregation class by name '" + className + "'", ex);
        }

        Object object;
        try
        {
            object = clazz.newInstance();
        }
        catch (InstantiationException e)
        {
            throw new EngineImportException("Error instantiating aggregation class by name '" + className + "'", e);
        }
        catch (IllegalAccessException e)
        {
            throw new EngineImportException("Illegal access instatiating aggregation function class by name '" + className + "'", e);
        }

        if (!(object instanceof AggregationSupport))
        {
            throw new EngineImportException("Aggregation class by name '" + className + "' does not subclass AggregationSupport");
        }
        return (AggregationSupport) object;
    }

    public AggregationFunctionFactory resolveAggregationFactory(String name) throws EngineImportUndefinedException, EngineImportException {
        ConfigurationPlugInAggregationFunction desc = aggregationFunctions.get(name);
        if (desc == null)
        {
            desc = aggregationFunctions.get(name.toLowerCase());
        }
        if (desc == null || desc.getFactoryClassName() == null)
        {
            throw new EngineImportUndefinedException("A function named '" + name + "' is not defined");
        }

        String className = desc.getFactoryClassName();
        Class clazz;
        try
        {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            clazz = Class.forName(className, true, cl);
        }
        catch (ClassNotFoundException ex)
        {
            throw new EngineImportException("Could not load aggregation class by name '" + className + "'", ex);
        }

        Object object;
        try
        {
            object = clazz.newInstance();
        }
        catch (InstantiationException e)
        {
            throw new EngineImportException("Error instantiating aggregation class by name '" + className + "'", e);
        }
        catch (IllegalAccessException e)
        {
            throw new EngineImportException("Illegal access instatiating aggregation function class by name '" + className + "'", e);
        }

        if (!(object instanceof AggregationFunctionFactory))
        {
            throw new EngineImportException("Aggregation class by name '" + className + "' does not implement AggregationFunctionFactory");
        }
        return (AggregationFunctionFactory) object;
    }

    public void addAggregationMultiFunction(ConfigurationPlugInAggregationMultiFunction desc) throws EngineImportException {
        LinkedHashSet<String> orderedImmutableFunctionNames = new LinkedHashSet<String>();
        for (String functionName : desc.getFunctionNames()) {
            orderedImmutableFunctionNames.add(functionName.toLowerCase());
            validateFunctionName("aggregation multi-function", functionName.toLowerCase());
        }
        if(!isClassName(desc.getMultiFunctionFactoryClassName()))
        {
            throw new EngineImportException("Invalid class name for aggregation multi-function factory '" + desc.getMultiFunctionFactoryClassName() + "'");
        }
        aggregationAccess.add(new Pair<Set<String>, ConfigurationPlugInAggregationMultiFunction>(orderedImmutableFunctionNames, desc));
    }

    public ConfigurationPlugInAggregationMultiFunction resolveAggregationMultiFunction(String name) {
        for (Pair<Set<String>, ConfigurationPlugInAggregationMultiFunction> config : aggregationAccess) {
            if (config.getFirst().contains(name.toLowerCase())) {
                return config.getSecond();
            }
        }
        return null;
    }

    public Pair<Class, EngineImportSingleRowDesc> resolveSingleRow(String name) throws EngineImportException, EngineImportUndefinedException
    {
        EngineImportSingleRowDesc pair = singleRowFunctions.get(name);
        if (pair == null)
        {
            pair = singleRowFunctions.get(name.toLowerCase());
        }
        if (pair == null)
        {
            throw new EngineImportUndefinedException("A function named '" + name + "' is not defined");
        }

        Class clazz;
        try
        {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            clazz = Class.forName(pair.getClassName(), true, cl);
        }
        catch (ClassNotFoundException ex)
        {
            throw new EngineImportException("Could not load single-row function class by name '" + pair.getClassName() + "'", ex);
        }
        return new Pair<Class, EngineImportSingleRowDesc>(clazz, pair);
    }

    public Method resolveMethod(String className, String methodName, Class[] paramTypes, boolean[] allowEventBeanType, boolean[] allowEventBeanCollType)
            throws EngineImportException
    {
        Class clazz;
        try
        {
            clazz = resolveClassInternal(className, false);
        }
        catch (ClassNotFoundException e)
        {
            throw new EngineImportException("Could not load class by name '" + className + "', please check imports", e);
        }

        try
        {
            return MethodResolver.resolveMethod(clazz, methodName, paramTypes, false, allowEventBeanType, allowEventBeanCollType);
        }
        catch (EngineNoSuchMethodException e)
        {
            throw convert(clazz, methodName, paramTypes, e, false);
        }
    }

    public Constructor resolveCtor(Class clazz, Class[] paramTypes) throws EngineImportException {
        try
        {
            return MethodResolver.resolveCtor(clazz, paramTypes);
        }
        catch (EngineNoSuchCtorException e)
        {
            throw convert(clazz, paramTypes, e);
        }
    }

    public Method resolveMethod(String className, String methodName)
			throws EngineImportException
    {
        Class clazz;
        try
        {
            clazz = resolveClassInternal(className, false);
        }
        catch (ClassNotFoundException e)
        {
            throw new EngineImportException("Could not load class by name '" + className + "', please check imports", e);
        }

        Method methods[] = clazz.getMethods();
        Method methodByName = null;

        // check each method by name
        for (Method method : methods)
        {
            if (method.getName().equals(methodName))
            {
                if (methodByName != null)
                {
                    throw new EngineImportException("Ambiguous method name: method by name '" + methodName + "' is overloaded in class '" + className + "'");
                }
                int modifiers = method.getModifiers();
                if (Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers))
                {
                    methodByName = method;
                }
            }
        }

        if (methodByName == null)
        {
            throw new EngineImportException("Could not find static method named '" + methodName + "' in class '" + className + "'");
        }
        return methodByName;
    }

    public Class resolveClass(String className)
			throws EngineImportException
    {
        Class clazz;
        try
        {
            clazz = resolveClassInternal(className, false);
        }
        catch (ClassNotFoundException e)
        {
            throw new EngineImportException("Could not load class by name '" + className + "', please check imports", e);
        }

        return clazz;
    }

    public Class resolveAnnotation(String className) throws EngineImportException {
        Class clazz;
        try
        {
            clazz = resolveClassInternal(className, true);
        }
        catch (ClassNotFoundException e)
        {
            throw new EngineImportException("Could not load annotation class by name '" + className + "', please check imports", e);
        }

        return clazz;
    }

    /**
     * Finds a class by class name using the auto-import information provided.
     * @param className is the class name to find
     * @return class
     * @throws ClassNotFoundException if the class cannot be loaded
     */
    protected Class resolveClassInternal(String className, boolean requireAnnotation) throws ClassNotFoundException
    {
		// Attempt to retrieve the class with the name as-is
		try
		{
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            return Class.forName(className, true, cl);
		}
		catch(ClassNotFoundException e)
        {
            if (log.isDebugEnabled())
            {
                log.debug("Class not found for resolving from name as-is '" + className + "'");
            }
        }

		// Try all the imports
		for(String importName : imports)
		{
			boolean isClassName = isClassName(importName);

			// Import is a class name
			if(isClassName)
			{
				if(importName.endsWith(className))
				{
                    ClassLoader cl = Thread.currentThread().getContextClassLoader();
                    return Class.forName(importName, true, cl);
				}

                String prefixedClassName = importName + '$' + className;
                try
                {
                    ClassLoader cl = Thread.currentThread().getContextClassLoader();
                    Class clazz = Class.forName(prefixedClassName, true, cl);
                    if (!requireAnnotation || clazz.isAnnotation()) {
                        return clazz;
                    }
                }
                catch(ClassNotFoundException e){
                    if (log.isDebugEnabled())
                    {
                        log.debug("Class not found for resolving from name '" + prefixedClassName + "'");
                    }
                }
			}
			else
			{
				// Import is a package name
				String prefixedClassName = getPackageName(importName) + '.' + className;
				try
				{
                    ClassLoader cl = Thread.currentThread().getContextClassLoader();
                    Class clazz = Class.forName(prefixedClassName, true, cl);
                    if (!requireAnnotation || clazz.isAnnotation()) {
                        return clazz;
                    }
				}
				catch(ClassNotFoundException e){
                    if (log.isDebugEnabled())
                    {
                        log.debug("Class not found for resolving from name '" + prefixedClassName + "'");
                    }
                }
			}
		}

        // try to resolve from method references
        for (String name : methodInvocationRef.keySet())
        {
            if (JavaClassHelper.isSimpleNameFullyQualfied(className, name))
            {
                try
                {
                    ClassLoader cl = Thread.currentThread().getContextClassLoader();
                    Class clazz = Class.forName(name, true, cl);
                    if (!requireAnnotation || clazz.isAnnotation()) {
                        return clazz;
                    }
                }
                catch (ClassNotFoundException e1)
                {
                    if (log.isDebugEnabled())
                    {
                        log.debug("Class not found for resolving from method invocation ref:" + name);
                    }
                }
            }
        }

        // No import worked, the class isn't resolved
		throw new ClassNotFoundException("Unknown class " + className);
	}

    public Method resolveMethod(Class clazz, String methodName, Class[] paramTypes, boolean[] allowEventBeanType, boolean[] allowEventBeanCollType)
			throws EngineImportException
    {
        try
        {
            return MethodResolver.resolveMethod(clazz, methodName, paramTypes, true, allowEventBeanType, allowEventBeanType);
        }
        catch (EngineNoSuchMethodException e)
        {
            throw convert(clazz, methodName, paramTypes, e, true);
        }
    }

    private EngineImportException convert(Class clazz, String methodName, Class[] paramTypes, EngineNoSuchMethodException e, boolean isInstance)
    {
        String expected = JavaClassHelper.getParameterAsString(paramTypes);
        String message = "Could not find ";
        if (!isInstance) {
            message += "static ";
        }
        else {
            message += "enumeration method, date-time method or instance ";
        }

        if (paramTypes.length > 0)
        {
            message += "method named '" + methodName + "' in class '" + JavaClassHelper.getClassNameFullyQualPretty(clazz) + "' with matching parameter number and expected parameter type(s) '" + expected + "'";
        }
        else
        {
            message += "method named '" + methodName + "' in class '" + JavaClassHelper.getClassNameFullyQualPretty(clazz) + "' taking no parameters";
        }

        if (e.getNearestMissMethod() != null)
        {
            message += " (nearest match found was '" + e.getNearestMissMethod().getName();
            if (e.getNearestMissMethod().getParameterTypes().length == 0) {
                message += "' taking no parameters";
            }
            else {
                message += "' taking type(s) '" + JavaClassHelper.getParameterAsString(e.getNearestMissMethod().getParameterTypes()) + "'";
            }
            message += ")";
        }
        return new EngineImportException(message, e);
    }

    private EngineImportException convert(Class clazz, Class[] paramTypes, EngineNoSuchCtorException e)
    {
        String expected = JavaClassHelper.getParameterAsString(paramTypes);
        String message = "Could not find constructor ";
        if (paramTypes.length > 0)
        {
            message += "in class '" + JavaClassHelper.getClassNameFullyQualPretty(clazz) + "' with matching parameter number and expected parameter type(s) '" + expected + "'";
        }
        else
        {
            message += "in class '" + JavaClassHelper.getClassNameFullyQualPretty(clazz) + "' taking no parameters";
        }

        if (e.getNearestMissCtor() != null)
        {
            message += " (nearest matching constructor ";
            if (e.getNearestMissCtor().getParameterTypes().length == 0) {
                message += "taking no parameters";
            }
            else {
                message += "taking type(s) '" + JavaClassHelper.getParameterAsString(e.getNearestMissCtor().getParameterTypes()) + "'";
            }
            message += ")";
        }
        return new EngineImportException(message, e);
    }

    public ExprNode resolveAggExtendedBuiltin(String name, boolean isDistinct) {
        if (!allowExtendedAggregationFunc) {
            return null;
        }
        if (name.toLowerCase().equals("firstever"))
        {
            return new ExprFirstEverNode(isDistinct);
        }
        if (name.toLowerCase().equals("lastever"))
        {
            return new ExprLastEverNode(isDistinct);
        }
        if (name.toLowerCase().equals("rate"))
        {
            return new ExprRateAggNode(isDistinct);
        }
        if (name.toLowerCase().equals("nth"))
        {
            return new ExprNthAggNode(isDistinct);
        }
        if (name.toLowerCase().equals("leaving"))
        {
            return new ExprLeavingAggNode(isDistinct);
        }
        if (name.toLowerCase().equals("maxby"))
        {
            return new ExprAggMultiFunctionSortedMinMaxByNode(true, false, false);
        }
        if (name.toLowerCase().equals("maxbyever"))
        {
            return new ExprAggMultiFunctionSortedMinMaxByNode(true, true, false);
        }
        if (name.toLowerCase().equals("minby"))
        {
            return new ExprAggMultiFunctionSortedMinMaxByNode(false, false, false);
        }
        if (name.toLowerCase().equals("minbyever"))
        {
            return new ExprAggMultiFunctionSortedMinMaxByNode(false, true, false);
        }
        if (name.toLowerCase().equals("sorted"))
        {
            return new ExprAggMultiFunctionSortedMinMaxByNode(false, false, true);
        }
        return null;
    }

    public MathContext getDefaultMathContext() {
        return optionalDefaultMathContext;
    }

    public boolean isSortUsingCollator() {
        return sortUsingCollator;
    }

    /**
     * For testing, returns imports.
     * @return returns auto-import list as array
     */
    protected String[] getImports()
	{
		return imports.toArray(new String[imports.size()]);
	}

    private static boolean isFunctionName(String functionName)
    {
        String classNameRegEx = "\\w+";
        return functionName.matches(classNameRegEx);
    }

	private static boolean isClassName(String importName)
	{
		String classNameRegEx = "(\\w+\\.)*\\w+(\\$\\w+)?";
		return importName.matches(classNameRegEx);
	}

	private static boolean isPackageName(String importName)
	{
		String classNameRegEx = "(\\w+\\.)+\\*";
		return importName.matches(classNameRegEx);
	}

	// Strip off the final ".*"
	private static String getPackageName(String importName)
	{
		return importName.substring(0, importName.length() - 2);
	}

    private void validateFunctionName(String functionType, String functionName) throws EngineImportException {
        String functionNameLower = functionName.toLowerCase();
        if (aggregationFunctions.containsKey(functionNameLower))
        {
            throw new EngineImportException("Aggregation function by name '" + functionName + "' is already defined");
        }
        if (singleRowFunctions.containsKey(functionNameLower))
        {
            throw new EngineImportException("Single-row function by name '" + functionName + "' is already defined");
        }
        for (Pair<Set<String>, ConfigurationPlugInAggregationMultiFunction> pairs : aggregationAccess) {
            if (pairs.getFirst().contains(functionNameLower)) {
                throw new EngineImportException("Aggregation multi-function by name '" + functionName + "' is already defined");
            }
        }
        if(!isFunctionName(functionName))
        {
            throw new EngineImportException("Invalid " + functionType + " name '" + functionName + "'");
        }
    }
}
