/*******************************************************************************
 * Copyright (c) 2015 Karim Ali and Ondřej Lhoták.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Karim Ali - initial API and implementation and/or initial documentation
 *******************************************************************************/
package averroes.soot;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.xmlpull.v1.XmlPullParserException;

import soot.ArrayType;
import soot.Body;
import soot.Local;
import soot.Modifier;
import soot.RefLikeType;
import soot.RefType;
import soot.SootClass;
import soot.SootField;
import soot.SootMethod;
import soot.SootMethodRef;
import soot.SourceLocator;
import soot.Type;
import soot.Value;
import soot.VoidType;
import soot.jimple.IntConstant;
import soot.jimple.InvokeExpr;
import soot.jimple.Jimple;
import soot.jimple.JimpleBody;
import soot.options.Options;
import soot.util.JasminOutputStream;
import averroes.android.AndroidEntryPointConstants;
import averroes.android.SetupAndroid;
import averroes.options.AverroesOptions;
import averroes.tamiflex.TamiFlexFactsDatabase;
import averroes.util.io.Paths;

/**
 * The master-mind of Averroes. That's where the magic of generating code for
 * library classes happen.
 * 
 * @author karim
 * 
 */
public class CodeGenerator {

	private static CodeGenerator instance = new CodeGenerator();

	private HashMap<SootClass, SootClass> libraryInterfaceToConcreteImplementationClass;
	private HashMap<SootClass, SootClass> abstractLibraryClassToConcreteImplementationClass;

	private int generatedMethodCount;
	private int generatedClassCount;

	private SootClass averroesLibraryClass = null;
	private SootClass averroesAbstractLibraryClass = null;
	private AverroesJimpleBody doItAllBody = null;

	/**
	 * Get the CodeGenerator singleton.
	 * 
	 * @return
	 */
	public static CodeGenerator v() {
		return instance;
	}

	/**
	 * Create a new code generator with the given class Hierarchy.v().
	 */
	private CodeGenerator() {
		libraryInterfaceToConcreteImplementationClass = new HashMap<SootClass, SootClass>();
		abstractLibraryClassToConcreteImplementationClass = new HashMap<SootClass, SootClass>();

		generatedMethodCount = 0;
		generatedClassCount = 0;

		initialize();
	}

	/**
	 * Get the number of generated methods.
	 * 
	 * @return
	 */
	public int getGeneratedMethodCount() {
		return generatedMethodCount;
	}

	/**
	 * Get the number of generated classes.
	 * 
	 * @return
	 */
	public int getGeneratedClassCount() {
		return generatedClassCount;
	}

	/**
	 * Get the concrete implementation class for the given library interface.
	 * 
	 * @param libraryInterface
	 * @return
	 */
	public SootClass getConcreteImplementationClassOfLibraryInterface(SootClass libraryInterface) {
		return libraryInterfaceToConcreteImplementationClass.get(libraryInterface);
	}

	/**
	 * Get the Averroes library class where all the fun takes place ;)
	 * 
	 * @return
	 */
	public SootClass getAverroesLibraryClass() {
		return averroesLibraryClass;
	}

	/**
	 * Get the Averroes abstract library class
	 * 
	 * @return
	 */
	public SootClass getAverroesAbstractLibraryClass() {
		return averroesAbstractLibraryClass;
	}

	/**
	 * Get the doItAll method.
	 * 
	 * @return
	 */
	public SootMethod getAverroesAbstractDoItAll() {
		return averroesAbstractLibraryClass
				.getMethod(Hierarchy.signatureToSubsignature(Names.AVERROES_ABSTRACT_DO_IT_ALL_METHOD_SIGNATURE));
	}

	/**
	 * Get the libraryPointsTo field.
	 * 
	 * @return
	 */
	public SootField getAverroesLibraryPointsTo() {
		return averroesAbstractLibraryClass
				.getField(Hierarchy.signatureToSubsignature(Names.LIBRARY_POINTS_TO_FIELD_SIGNATURE));
	}

	/**
	 * Get the finalizePointsTo field.
	 * 
	 * @return
	 */
	public SootField getAverroesFinalizePointsTo() {
		return averroesAbstractLibraryClass
				.getField(Hierarchy.signatureToSubsignature(Names.FINALIZE_POINTS_TO_FIELD_SIGNATURE));
	}

	/**
	 * Get the instance field.
	 * 
	 * @return
	 */
	public SootField getAverroesInstanceField() {
		return averroesAbstractLibraryClass.getField(Hierarchy.signatureToSubsignature(Names.INSTANCE_FIELD_SIGNATURE));
	}

	/**
	 * Create the Averroes library class where all the fun takes place ;)
	 * 
	 * @throws IOException
	 */
	public void createAverroesLibraryClass() throws IOException {
		// Create the abstract library class (specifically to be compatible with
		// WALA/Java).
		// This class represents the interface of the AverroesLibraryClass and
		// will be part of the primordial library that is added to WALA.
		if (averroesAbstractLibraryClass == null) {
			// Create the class
			averroesAbstractLibraryClass = new SootClass(Names.AVERROES_ABSTRACT_LIBRARY_CLASS, Modifier.PUBLIC);
			averroesAbstractLibraryClass.setSuperclass(Hierarchy.v().getJavaLangObject());

			// Create the constructor that calls the JavaLangObject constructor
			createAverroesAbstractLibraryInit();

			// Create the LPT field, FIN field, and instance field
			createAverroesAbstractLibraryFields();

			// Create the abstract doItAll method
			createAverroesAbstractLibraryDoItAll();

			// Write the class file to disk
			writeLibraryClassFile(averroesAbstractLibraryClass);
		}

		// Now create the AverroesLibraryClass which basically implements the
		// doItAll method and assigns itself to AbstractLibrary.instance
		// This class could then be treated as part of the application, which
		// leads to (in the future) just regenerating this class instead of
		// regenerating the whole placeholder library
		if (averroesLibraryClass == null) {
			// Create the class
			averroesLibraryClass = new SootClass(Names.AVERROES_LIBRARY_CLASS, Modifier.PUBLIC);
			averroesLibraryClass.setSuperclass(averroesAbstractLibraryClass);

			// Add a default empty constructor
			createAverroesLibraryInit();

			// Create its static initalizer
			createAverroesLibraryClinit();

			// If we are running in Android mode we need to add the dummy main
			/*
			 * if (AverroesOptions.isAndroid()) { createAverroesLibraryDummyMain(); }
			 */

			// Create the dotItAll method
			createAverroesLibraryDoItAll();

			// Write the class file to disk
			writeLibraryClassFile(averroesLibraryClass);
		}
	}

	/**
	 * Create the bodies of library methods.
	 * 
	 * @throws IOException
	 */
	public void createLibraryMethodBodies() throws IOException {
		Set<SootClass> AllLibraryClasses = new HashSet<SootClass>();

		// Phantom classes cannot have methods with bodies,
		// So we want to ignore these classes in case of android
		// They are handled separately.
		if (AverroesOptions.isAndroid()) {
			AllLibraryClasses = getNonPhantomLibraryClasses();
		} else {
			AllLibraryClasses = getLibraryClasses();
		}

		for (SootClass libraryClass : AllLibraryClasses) {

			// explicitly setting its super class to avoid RuntimeException
			if (AverroesOptions.isAndroid() && !libraryClass.hasSuperclass()
					&& !libraryClass.getName().equals("java.lang.Object")) {
				libraryClass.setSuperclass(Hierarchy.v().getJavaLangObject());
			}
			for (SootMethod method : libraryClass.getMethods()) {
				// Create our Jimple body for concrete methods only
				if (method.isConcrete()) {
					createJimpleBody(method);
				}
			}

			writeLibraryClassFile(libraryClass);
		}
	}

	/**
	 * Write the class file for the given library class.
	 * 
	 * @param cls
	 * @throws IOException
	 */
	public static void writeLibraryClassFile(SootClass cls) throws IOException {
		Options.v().set_output_dir(Paths.libraryClassesOutputDirectory().getPath());

		File file = new File(SourceLocator.v().getFileNameFor(cls, Options.output_format_class));
		file.getParentFile().mkdirs();

		OutputStream streamOut = new JasminOutputStream(new FileOutputStream(file));
		PrintWriter writerOut = new PrintWriter(new OutputStreamWriter(streamOut));

		if (cls.containsBafBody()) {
			new soot.baf.JasminClass(cls).print(writerOut);
		} else {

			new soot.jimple.JasminClass(cls).print(writerOut);
		}

		writerOut.flush();
		streamOut.close();
	}

	/**
	 * Create the Jimple body for the given library method. If it's a constructor,
	 * then we need to initialize all the fields in the class with objects
	 * compatible from the LPT. If it's the static initializer, then we need to
	 * initialize all the static fields of the class with objects compatible from
	 * the LPT. method.
	 * 
	 * @param method
	 * 
	 * @return
	 */
	private JimpleBody createJimpleBody(SootMethod method) {
		if (method.getDeclaringClass().getName().equals(Names.AVERROES_ABSTRACT_LIBRARY_CLASS)
				|| method.getDeclaringClass().getName().equals(Names.AVERROES_LIBRARY_CLASS)) {
			throw new IllegalArgumentException("Creating Jimple body for " + method.getSignature()
					+ ". We should never enter createJimpleBody() for the Averroes library classes.");
		}

		// Create a basic Jimple body
		AverroesJimpleBody body = new AverroesJimpleBody(method);

		// Insert the appropriate method body
		if (body.isConstructor()) {
			body.initializeInstanceFields();
		} else if (body.isStaticInitializer()) {
			body.initializeStaticFields();
		} else {
			// the standard library method will have nothing more in its body
		}

		// Insert the standard Jimple body footer
		body.insertStandardJimpleBodyFooter();

		// Validate the Jimple body
		body.validate();

		// TODO
		// System.out.println(body.getJimpleBody());

		return (JimpleBody) method.getActiveBody();
	}

	/**
	 * Generate the name of any class that Averroes creates.
	 * 
	 * @param cls
	 * @return
	 */
	private String generateCraftedClassName(SootClass cls) {
		return cls.getName().concat("__Averroes");
	}

	/**
	 * Initialize the code generator by creating the concrete implementation
	 * classes.
	 */
	private void initialize() {
		implementLibraryInterfacesNotImplementedInLibrary();
		implementAbstractLibraryClassesNotImplementedInLibrary();
	}

	/**
	 * Implement any library interface that is not implemented in the library.
	 */
	private void implementLibraryInterfacesNotImplementedInLibrary() {
		for (SootClass iface : Hierarchy.v().getLibraryInterfacesNotImplementedInLibrary()) {
			SootClass cls = createLibraryClassImplementsInterface(iface);
			libraryInterfaceToConcreteImplementationClass.put(iface, cls);
		}
	}

	/**
	 * Create a concrete library subclass for any abstract library class that is not
	 * implemented in the library.
	 */
	private void implementAbstractLibraryClassesNotImplementedInLibrary() {
		for (SootClass cls : Hierarchy.v().getAbstractLibraryClassesNotImplementedInLibrary()) {
			SootClass concrete = createConcreteLibrarySubclassFor(cls);
			abstractLibraryClassToConcreteImplementationClass.put(cls, concrete);
		}
	}

	/**
	 * Create the default constructor for the Averroes library class.
	 */
	private void createAverroesAbstractLibraryInit() {
		SootMethod init = Hierarchy.getNewDefaultConstructor();

		JimpleBody body = Jimple.v().newBody(init);
		init.setActiveBody(body);
		averroesAbstractLibraryClass.addMethod(init);

		// Call superclass constructor
		body.insertIdentityStmts();
		body.getUnits()
				.add(Jimple.v()
						.newInvokeStmt(Jimple.v().newSpecialInvokeExpr(body.getThisLocal(),
								Hierarchy.getDefaultConstructor(Hierarchy.v().getJavaLangObject()).makeRef(),
								Collections.<Value>emptyList())));

		// Add return statement
		body.getUnits().addLast(Jimple.v().newReturnVoidStmt());

		// Finally validate the Jimple body
		body.validate();
	}

	/**
	 * Add the main 3 fields to the AverroesAbstractLibrary class.
	 */
	private void createAverroesAbstractLibraryFields() {
		SootField libraryPointsTo = new SootField(Names.LIBRARY_POINTS_TO, Hierarchy.v().getJavaLangObject().getType(),
				Modifier.PUBLIC);
		SootField finalizePointsTo = new SootField(Names.FINALIZE_POINTS_TO,
				Hierarchy.v().getJavaLangObject().getType(), Modifier.PUBLIC);
		SootField instance = new SootField(Names.INSTANCE, averroesAbstractLibraryClass.getType(),
				Modifier.PUBLIC | Modifier.STATIC);
		averroesAbstractLibraryClass.addField(libraryPointsTo);
		averroesAbstractLibraryClass.addField(finalizePointsTo);
		averroesAbstractLibraryClass.addField(instance);
	}

	/**
	 * Add the abstract doItAll method to the AverroesAbstractLibrary class.
	 */
	private void createAverroesAbstractLibraryDoItAll() {
		SootMethod doItAll = new SootMethod(Names.AVERROES_DO_IT_ALL_METHOD_NAME, Collections.<Type>emptyList(),
				VoidType.v(), Modifier.PUBLIC | Modifier.ABSTRACT);
		averroesAbstractLibraryClass.addMethod(doItAll);
	}

	/**
	 * Create the static initializer for the Averroes library class.
	 */
	private void createAverroesLibraryInit() {
		SootMethod init = Hierarchy.getNewDefaultConstructor();

		JimpleBody body = Jimple.v().newBody(init);
		init.setActiveBody(body);
		averroesLibraryClass.addMethod(init);

		// Call superclass constructor
		body.insertIdentityStmts();
		body.getUnits()
				.add(Jimple.v()
						.newInvokeStmt(Jimple.v().newSpecialInvokeExpr(body.getThisLocal(),
								Hierarchy.getDefaultConstructor(averroesAbstractLibraryClass).makeRef(),
								Collections.<Value>emptyList())));

		// Add return statement
		body.getUnits().addLast(Jimple.v().newReturnVoidStmt());

		// Finally validate the Jimple body
		body.validate();
	}

	/**
	 * Create the default constructor for the Averroes library class.
	 */
	private void createAverroesLibraryClinit() {
		SootMethod clinit = new SootMethod(SootMethod.staticInitializerName, Collections.<Type>emptyList(),
				VoidType.v(), Modifier.PUBLIC | Modifier.STATIC);

		AverroesJimpleBody body = new AverroesJimpleBody(clinit);
		averroesLibraryClass.addMethod(clinit);

		// Create instance and call the constructor
		Local instance = body.insertNewStatement(RefType.v(averroesLibraryClass));
		body.insertSpecialInvokeStatement(instance, averroesLibraryClass.getMethod(Names.DEFAULT_CONSTRUCTOR_SIG));

		// Now assign this instance to AverroesAbstractLibrary.instance
		body.storeStaticField(CodeGenerator.v().getAverroesInstanceField(), instance);

		// Add return statement
		body.insertReturnStmt();

		// System.out.println(clinitBody.getJimpleBody());

		// Finally validate the Jimple body
		body.validate();
	}

	/**
	 * If running in Android mode, this method adds the dummy main to the Averroes
	 * library class.
	 * 
	 * @throws XmlPullParserException
	 * @throws IOException
	 * @deprecated
	 */
	/*private void createAverroesLibraryDummyMain() throws IOException, XmlPullParserException {
		SootMethod dM = SetupAndroid.v().getDummyMainMethod();
		Type stringArrayType = ArrayType.v(RefType.v("java.lang.String"), 1);
		SootMethod dummyMain = new SootMethod(Names.AVERROES_DUMMY_MAIN_METHOD_NAME,
				Collections.singletonList(stringArrayType), VoidType.v(), Modifier.PUBLIC | Modifier.STATIC);

		averroesLibraryClass.addMethod(dummyMain);
		Body b = dM.getActiveBody();
		dummyMain.setActiveBody(b);
		dummyMain.getActiveBody().validate();
		b.validate();
	}*/

	/**
	 * Create the doItAll method for the Averroes library class. It includes
	 * creating objects, calling methods, writing to array elements, throwing
	 * exceptions and all the stuff that the library could do.
	 */
	private void createAverroesLibraryDoItAll() {
		SootMethod doItAll = new SootMethod(Names.AVERROES_DO_IT_ALL_METHOD_NAME, Collections.<Type>emptyList(),
				VoidType.v(), Modifier.PUBLIC);

		averroesLibraryClass.addMethod(doItAll);
		doItAllBody = new AverroesJimpleBody(doItAll);

		// Insert object creation statements
		createObjects();

		// Call finalize on all the objects in FPT
		callFinalize();

		// Call all the application methods that the library could call
		// reflectively
		callApplicationMethodsReflectively();

		// Handle array indices: cast lpt to object[] then assign it lpt
		handleArrayIndices();

		// Create, reflectively, the application objects, and assign them to lpt
		if (AverroesOptions.isTamiflexEnabled()) {
			createObjectsFromApplicationClassNames();
		}

		// Now we need to throw all the exceptions the library has access to
		// (via lpt)
		throwThrowables();

		// Add return statement
		// NOTE: We should ignore the return statement in this method as the
		// last statement will be the "throw"
		// statement, and the return type is void.
		// body.insertReturnStmt();

		// TODO
		// System.out.println(doItAllBody.getJimpleBody());

		// Finally validate the Jimple body
		doItAllBody.validate();
	}

	/**
	 * Call the finalize() method.
	 * 
	 */
	private void callFinalize() {
		Local fpt = doItAllBody.getFpt();
		SootMethod finalize = Hierarchy.v().getMethod(Names.FINALIZE_SIG);
		doItAllBody.insertVirtualInvokeStatement(fpt, finalize);
	}

	/**
	 * Call the application methods that the library could call reflectively.
	 */
	private void callApplicationMethodsReflectively() {
		for (SootMethod toCall : getAllMethodsToCallReflectively()) {

			SootClass cls = toCall.getDeclaringClass();
			// SootClass cls = Hierarchy.v().getClass(toCall.getSignature());
			SootMethodRef methodRef = toCall.makeRef();

			// Prepare the method base, and actual args
			Local base = (Local) doItAllBody.getCompatibleValue(cls.getType());
			List<Value> args = doItAllBody.prepareActualArguments(toCall);
			InvokeExpr invokeExpr;

			// Call the method
			if (cls.isInterface()) {
				invokeExpr = Jimple.v().newInterfaceInvokeExpr(base, methodRef, args);
			} else if (toCall.isStatic()) {
				invokeExpr = Jimple.v().newStaticInvokeExpr(methodRef, args);
			} else {
				invokeExpr = Jimple.v().newVirtualInvokeExpr(base, methodRef, args);
			}

			// Assign the return of the call to the return variable only if it
			// holds an object.
			// If not, then just call the method.
			if (toCall.getReturnType() instanceof RefLikeType) {
				Local ret = doItAllBody.newLocal(toCall.getReturnType());
				doItAllBody.getInvokeReturnVariables().add(ret);
				doItAllBody.insertAssignmentStatement(ret, invokeExpr);
			} else {
				doItAllBody.insertInvokeStatement(invokeExpr);
			}
		}

		// Assign the return values from all those methods only if there were
		// any return variables of type RefLikeType
		for (Local ret : doItAllBody.getInvokeReturnVariables()) {
			doItAllBody.storeLibraryPointsToField(ret);
		}

	}

	/**
	 * Checks if a method is a life cycle method or not. (android)
	 * 
	 * @param method
	 * @return
	 */
	// TODO: this method is being used in Hierarchy.java as well. Refactor?
	private boolean isLifeCycle(SootMethod method) {
		// TODO: Refactor (e.g. store the method signatures inside this class)?
		return AndroidEntryPointConstants.isLifecycleClass(method.getDeclaringClass().getName())
				&& AndroidEntryPointConstants.getComponentLifecycleMethods().contains(method.getSubSignature());
	}

	/**
	 * Retrieve all the methods that the library could call back through reflection.
	 * 
	 * @return
	 */
	public Set<SootMethod> getAllMethodsToCallReflectively() {
		LinkedHashSet<SootMethod> result = new LinkedHashSet<SootMethod>();
		result.addAll(Hierarchy.v().getLibrarySuperMethodsOfApplicationMethods());
		result.addAll(getTamiFlexApplicationMethodInvokes());

		// If it is android we want to ignore life cycle methods because
		// they are already being modeled in the dummy main.

		/*
		 * if (AverroesOptions.isAndroid()) { result.forEach(method -> { if
		 * (isLifeCycle(method)) { result.remove(method); }
		 * 
		 * }); }
		 */
		//TODO: phantom methods causing problems?
		if (AverroesOptions.isAndroid()) {
			result.removeIf(m -> !m.isDeclared());
		}

		return result;
	}

	/**
	 * Handle possible array writes in the library.
	 */
	private void handleArrayIndices() {
		Local objectArray = (Local) doItAllBody
				.getCompatibleValue(ArrayType.v(Hierarchy.v().getJavaLangObject().getType(), 1));
		doItAllBody.insertAssignmentStatement(Jimple.v().newArrayRef(objectArray, IntConstant.v(0)),
				doItAllBody.getLpt());
	}

	/**
	 * Create objects for application classes if the library knows their name
	 * constants.
	 */
	private void createObjectsFromApplicationClassNames() {
		SootMethod forName = Hierarchy.v().getMethod(Names.FOR_NAME_SIG);
		SootMethod newInstance = Hierarchy.v().getMethod(Names.NEW_INSTANCE_SIG);
		List<Value> args = doItAllBody.prepareActualArguments(forName);
		Local classes = doItAllBody.newLocal(Hierarchy.v().getJavaLangClass().getType());
		Local instances = doItAllBody.newLocal(Hierarchy.v().getJavaLangObject().getType());
		doItAllBody.insertAssignmentStatement(classes, Jimple.v().newStaticInvokeExpr(forName.makeRef(), args));
		doItAllBody.insertAssignmentStatement(instances,
				Jimple.v().newVirtualInvokeExpr(classes, newInstance.makeRef()));
		doItAllBody.storeLibraryPointsToField(instances);
	}

	/**
	 * Throw any throwable object pointed to by the LPT. NOTE: the throw statement
	 * has to be right before the return statement of the method, otherwise the
	 * method is invalid (Soot).
	 */
	private void throwThrowables() {
		Local throwables = (Local) doItAllBody.getCompatibleValue(Hierarchy.v().getJavaLangThrowable().getType());
		doItAllBody.insertThrowStatement(throwables);
	}

	/**
	 * Create all the objects that the library could possible instantiate.
	 * 
	 */
	private void createObjects() {
		// 1. The library can point to any concrete (i.e., not an interface nor
		// abstract) library class
		for (SootClass cls : getConcreteLibraryClasses()) {
			doItAllBody.createObjectOfType(cls);
		}

		// 2. Convert any use of application class name string constants to
		// explicit instantiations.
		for (SootClass cls : Hierarchy.v().getApplicationConstantPool().getApplicationClasses()) {
			if (!Hierarchy.isAbstractClass(cls) && !cls.isInterface()) {
				doItAllBody.createObjectOfType(cls);
			}
		}

		// 3. The library can create application objects through
		// Class.newInstance
		if (AverroesOptions.isTamiflexEnabled()) {
			for (SootClass cls : getTamiFlexApplicationClassNewInstance()) {
				doItAllBody.createObjectOfType(cls);
			}
		}

		// 4. The library can create application objects through
		// Constructor.newInstance
		if (AverroesOptions.isTamiflexEnabled()) {
			for (SootMethod init : getTamiFlexApplicationConstructorNewInstance()) {
				doItAllBody.createObjectByCallingConstructor(init);
			}
		}

		// 5. The library points to some certain objects of array types
		for (ArrayType type : getArrayTypesAccessibleToLibrary()) {
			doItAllBody.createObjectOfType(type);
		}

		// 6. The library could possibly create application objects whose class
		// names are passed to it through
		// calls to Class.forName
		if (AverroesOptions.isTamiflexEnabled()) {
			for (SootClass cls : getTamiFlexApplicationClassForName()) {
				doItAllBody.createObjectOfType(cls);
			}
		}

		// 7. Create instances of dynamic classes
		if (AverroesOptions.isDynamicClassesEnabled()) {
			try {
				for (String className : AverroesOptions.getDynamicApplicationClasses()) {
					doItAllBody.createObjectOfType(Hierarchy.v().getClass(className));
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * Get a set of all the array types accessible to the library.
	 * 
	 * @return
	 */
	private Set<ArrayType> getArrayTypesAccessibleToLibrary() {
		Set<ArrayType> result = new HashSet<ArrayType>();
		result.addAll(Hierarchy.v().getLibraryArrayTypeParameters());
		result.addAll(Hierarchy.v().getLibraryArrayTypeReturns());

		// Only added if reflection support is enabled
		if (AverroesOptions.isTamiflexEnabled()) {
			result.addAll(getTamiFlexApplicationArrayNewInstance());
		}

		return result;
	}

	/**
	 * Get a set of all the concrete library classes. This include the original
	 * concrete library classes, in addition to the concrete implementation classes
	 * generated by this code generator.
	 * 
	 * @return
	 */
	private Set<SootClass> getConcreteLibraryClasses() {
		Set<SootClass> result = new HashSet<SootClass>();
		result.addAll(Hierarchy.v().getConcreteLibraryClasses());
		result.addAll(abstractLibraryClassToConcreteImplementationClass.values());
		result.addAll(libraryInterfaceToConcreteImplementationClass.values());
		return result;
	}

	/**
	 * Get a set of all the library classes. This include the original library
	 * classes, in addition to the concrete implementation classes generated by this
	 * code generator.
	 * 
	 * @return
	 */
	private Set<SootClass> getLibraryClasses() {
		Set<SootClass> result = new HashSet<SootClass>();
		result.addAll(Hierarchy.v().getLibraryClasses());
		result.addAll(abstractLibraryClassToConcreteImplementationClass.values());
		result.addAll(libraryInterfaceToConcreteImplementationClass.values());
		return result;
	}

	/**
	 * Get a set of all the library classes except those which are phantom.
	 * 
	 * @return
	 */

	private Set<SootClass> getNonPhantomLibraryClasses() {

		Set<SootClass> result = new HashSet<SootClass>();
		result.addAll(getLibraryClasses());

		// Removing all phantom classes.
		// Note: this does not remove them from Scene.
		result.removeIf(cls -> (cls.isPhantomClass()));

		return result;
	}

	/**
	 * Get a set of all the phantom library classes
	 * 
	 * @return
	 */

	public Set<SootClass> getPhantomLibraryCLasses() {

		Set<SootClass> phantomClasses = new HashSet<SootClass>();
		Set<SootClass> result = new HashSet<SootClass>();

		phantomClasses.addAll(getLibraryClasses());

		// Removing all non-phantom classes.
		// Note: this does not remove them from Scene.
		phantomClasses.removeIf(cls -> (!cls.isPhantomClass()));

		// creating empty soot classes for the phantom classes
		for (SootClass pClass : phantomClasses) {
			SootClass newPhantomClass = new SootClass(pClass.getName());
			for (SootMethod method : newPhantomClass.getMethods()) {
				if (method != null) {
					newPhantomClass.addMethod(method);
					AverroesJimpleBody body = new AverroesJimpleBody(method);
					body.insertStandardJimpleBodyFooter();
					body.validate();
				}
			}
			if (pClass.hasSuperclass()) {
				newPhantomClass.setSuperclass(pClass.getSuperclass());
			} else {
				newPhantomClass.setSuperclass(Hierarchy.v().getJavaLangObject());
			}

			result.add(newPhantomClass);

		}

		return result;

	}

	/**
	 * Find all the application methods that TamiFlex found out they could be called
	 * reflectively.
	 * 
	 * @return
	 */
	private Set<SootMethod> getTamiFlexApplicationMethodInvokes() {
		Set<SootMethod> result = new HashSet<SootMethod>();

		for (String methodSignature : TamiFlexFactsDatabase.getMethodInvoke()) {
			if (Hierarchy.v().isApplicationMethod(methodSignature)) {
				result.add(Hierarchy.v().getMethod(methodSignature));
			}
		}

		return result;
	}

	/**
	 * Find all the application classes that might be reflectively created through
	 * Class.forName.
	 * 
	 * @return
	 */
	private Set<SootClass> getTamiFlexApplicationClassForName() {
		Set<SootClass> result = new HashSet<SootClass>();

		for (String className : TamiFlexFactsDatabase.getClassForName()) {
			if (Hierarchy.v().isApplicationClass(className)) {
				result.add(Hierarchy.v().getClass(className));
			}
		}

		return result;
	}

	/**
	 * Get all the application array types that the library can create objects for.
	 * 
	 * @return
	 */
	private Set<ArrayType> getTamiFlexApplicationArrayNewInstance() {
		Set<ArrayType> result = new HashSet<ArrayType>();

		for (String arrayType : TamiFlexFactsDatabase.getArrayNewInstance()) {
			String baseType = Hierarchy.getBaseType(arrayType);
			if (Hierarchy.v().isApplicationClass(baseType)) {
				result.add(Hierarchy.v().getArrayType(arrayType));
			}
		}

		return result;
	}

	/**
	 * Find all the application classes that could be reflectively created through
	 * Class.newInstance.
	 * 
	 * @return
	 */
	private Set<SootClass> getTamiFlexApplicationClassNewInstance() {
		Set<SootClass> result = new HashSet<SootClass>();

		for (String className : TamiFlexFactsDatabase.getClassNewInstance()) {
			if (Hierarchy.v().isApplicationClass(className)) {
				result.add(Hierarchy.v().getClass(className));
			}
		}

		return result;
	}

	/**
	 * Find all the constructors that could be reflectively used to create
	 * application classes through Constructor.newInstance.
	 * 
	 * @return
	 */
	private Set<SootMethod> getTamiFlexApplicationConstructorNewInstance() {
		Set<SootMethod> result = new HashSet<SootMethod>();

		for (String methodSignature : TamiFlexFactsDatabase.getConstructorNewInstance()) {
			if (Hierarchy.v().isApplicationMethod(methodSignature)) {
				result.add(Hierarchy.v().getMethod(methodSignature));
			}
		}

		return result;
	}

	/**
	 * Create a class that implements the given abstract class.
	 * 
	 * @param abstractClass
	 * @return
	 */
	private SootClass createConcreteLibrarySubclassFor(SootClass abstractClass) {
		SootClass cls = new SootClass(generateCraftedClassName(abstractClass));
		// Set the superclass
		cls.setSuperclass(abstractClass);

		// Add the class to the Soot scene
		// Scene.v().addClass(cls);

		// Implement all the abstract methods in the super class.
		// The bodies of these methods will be handled later.
		for (SootMethod method : abstractClass.getMethods()) {
			if (method.isAbstract()) {
				addMethodToGeneratedClass(cls, getConcreteMethod(method));
			}
		}

		// Now add a default constructor
		addDefaultConstructorToGeneratedClass(cls);

		// Set the resolving level to SIGNATURES and set this class to be a
		// library class.
		// cls.setResolvingLevel(SootClass.SIGNATURES);
		// cls.setLibraryClass();

		// Update the generated class count
		generatedClassCount++;

		return cls;
	}

	/**
	 * Create a class that implements the given interface.
	 * 
	 * @param iface
	 * @return
	 */
	private SootClass createLibraryClassImplementsInterface(SootClass iface) {
		SootClass cls = new SootClass(generateCraftedClassName(iface));

		// java.lang.Object is the superclass of all classes
		cls.setSuperclass(Hierarchy.v().getJavaLangObject());

		// Add the class to the Soot scene
		// Scene.v().addClass(cls);

		// Make the given Soot interface a direct superinterface of the crafted
		// class.
		cls.addInterface(iface);

		// Now we need to implement the methods in all the superinterfaces of
		// the newly crafted class.
		// The body of these methods will be created later.
		for (SootMethod method : getSuperinterfacesMethods(iface)) {
			addMethodToGeneratedClass(cls, getConcreteMethod(method));
		}

		// Now add a default constructor.
		addDefaultConstructorToGeneratedClass(cls);

		// Set the resolving level to SIGNATURES and set this class to be a
		// library class.
		// cls.setResolvingLevel(SootClass.SIGNATURES);
		// cls.setLibraryClass();

		// Update the generated class count
		generatedClassCount++;

		return cls;
	}

	/**
	 * Get all the non-repeated methods in the superinterfaces (i.e., non-repeated
	 * as in don't have the same subsignature.
	 * 
	 * @param iface
	 * @return
	 */
	private Collection<SootMethod> getSuperinterfacesMethods(SootClass iface) {
		Map<String, SootMethod> methods = new HashMap<String, SootMethod>();

		for (SootClass superInterface : Hierarchy.v().getSuperinterfacesOfIncluding(iface)) {
			for (SootMethod method : superInterface.getMethods()) {
				if (!methods.containsKey(method.getSubSignature())) {
					methods.put(method.getSubSignature(), method);
				}
			}
		}

		return methods.values();
	}

	/**
	 * Get the concrete version of an abstract method. This way Averroes will create
	 * a method body for it. This is important for implementing interface methods,
	 * and extending abstract classes.
	 * 
	 * @param libraryMethod
	 * @return
	 */
	private SootMethod getConcreteMethod(SootMethod method) {
		return new SootMethod(method.getName(), method.getParameterTypes(), method.getReturnType(),
				method.getModifiers() & ~Modifier.ABSTRACT, method.getExceptions());
	}

	/**
	 * Add a method to a generated class and update the generated method count.
	 * 
	 * @param generatedClass
	 * @param method
	 */
	private void addMethodToGeneratedClass(SootClass generatedClass, SootMethod method) {
		generatedClass.addMethod(method);

		// Update the generated method count
		generatedMethodCount++;
	}

	/**
	 * Add a default constructor to the given generated class.
	 * 
	 * @param generatedClass
	 */
	private void addDefaultConstructorToGeneratedClass(SootClass generatedClass) {
		if (!generatedClass.isInterface()) {
			if (Hierarchy.hasDefaultConstructor(generatedClass)) {
				Hierarchy.makePublic(generatedClass.getMethod(Names.DEFAULT_CONSTRUCTOR_SIG));
			} else {
				addMethodToGeneratedClass(generatedClass, Hierarchy.getNewDefaultConstructor());
			}
		}
	}

}
