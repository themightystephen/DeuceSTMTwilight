package org.deuce.transform.core;

import java.lang.annotation.Annotation;
import java.util.LinkedList;

import org.deuce.objectweb.asm.AnnotationVisitor;
import org.deuce.objectweb.asm.ClassVisitor;
import org.deuce.objectweb.asm.FieldVisitor;
import org.deuce.objectweb.asm.MethodVisitor;
import org.deuce.objectweb.asm.Opcodes;
import org.deuce.objectweb.asm.Type;
import org.deuce.objectweb.asm.commons.Method;
import org.deuce.transaction.Context;
import org.deuce.transaction.ContextDelegator;
import org.deuce.transform.commons.BaseClassTransformer;
import org.deuce.transform.commons.Exclude;
import org.deuce.transform.commons.Field;
import org.deuce.transform.commons.FieldsHolder;
import org.deuce.transform.commons.type.TypeCodeResolver;
import org.deuce.transform.commons.type.TypeCodeResolverFactory;
import org.deuce.transform.commons.util.Util;
import org.deuce.transform.core.method.MethodTransformer;
import org.deuce.transform.core.method.StaticMethodTransformer;

/**
 * Class-level visitor for performing transformations on bytecode to call into Twilight API.
 *
 * @author Stephen Tuttlebee
 */
@Exclude
public class ClassTransformer extends BaseClassTransformer implements FieldsHolder{
	final private static String ENUM_DESC = Type.getInternalName(Enum.class);

	private boolean exclude = false;
	private boolean visitclinit = false;
	final private LinkedList<Field> fields = new LinkedList<Field>(); // TODO: are these the synthetic fields???
	private String staticField = null; // non-null value indicates we need to add __CLASS_BASE__ to instrumented class (see StaticMethodTransformer)

	final static public String EXCLUDE_DESC = Type.getDescriptor(Exclude.class);
	final static private String ANNOTATION_NAME = Type.getInternalName(Annotation.class);
	private boolean isInterface;
	private boolean isEnum;
	private MethodVisitor staticMethod;

	// haven't fully worked out what this is for. FieldsHolder holds NEW fields for transformed classes. Difference between Field and FieldsHolder? FieldsHolder is an interface. Field is a class.
	private final FieldsHolder fieldsHolder;

	public ClassTransformer(ClassVisitor cv, String className, FieldsHolder fieldsHolder){
		super(cv, className);
		// if fieldsHolder is null, then we're doing ONLINE instrumentation
		this.fieldsHolder = fieldsHolder == null ? this : fieldsHolder;
	}

	// visits the header of the class
	@Override
	public void visit(final int version, final int access, final String name,
			final String signature, final String superName, final String[] interfaces) {

		// TODO: when doing offline instrumentation, this will do some necessary visiting of its own and other preparation (see ExternalFieldsHolder class)
		// when fieldsHolder is an ExternalFieldsHolder (i.e. offline instrumentation), causes visits of header of new class for holding fields
		fieldsHolder.visit(superName); // the field holder classes can have a superclass other than Object. This happens whenever the class being transformed itself has a superclass. The DeuceFieldsHolder ends up having a superclass which has the same name as the transformed class's superclass except it is suffixed with DeuceFieldsHolder as well. Basically, the superclass hierarchy for DeuceFieldHolder classes corresponds to that of the transformed classes (containing the original fields).

		// store useful information for later
		isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
		isEnum = ENUM_DESC.equals(superName);

		for(String inter : interfaces){
			// if the class is an Annotation type, then exclude from transformation (see http://docs.oracle.com/javase/1.5.0/docs/api/java/lang/annotation/Annotation.html)
			if( inter.equals(ANNOTATION_NAME)){
				exclude = true;
				break;
			}
		}

		// visit class header as normal (TODO: we call visit on super here, ByteCodeVisitor, but all it does is something very simple that doesn't really need to be done there; it could just be done directly here)
		super.visit(version, access, name, signature, superName, interfaces);
	}

	/**
	 * Checks if the class is marked as {@link Exclude @Exclude}
	 */
	@Override
	public AnnotationVisitor visitAnnotation( String desc, boolean visible) {
		// if not excluded already, exclude if annotation descriptor is @Exclude
		exclude = exclude ? exclude : EXCLUDE_DESC.equals(desc);
		return super.visitAnnotation(desc, visible);
	}

	/**
	 * Creates a new (synthetic) static final field for each existing field.
	 * The field will be statically initialized to hold the field address.
	 */
	@Override
	public FieldVisitor visitField(int access, String name, String desc, String signature,
			Object value) {
		FieldVisitor fieldVisitor = super.visitField(access, name, desc, signature, value);
		// if the class is on exclude list, then obviously no transformations to be done, just return
		if(exclude)
			return fieldVisitor;

		// define synthetic "address" field as constant (i.e. static final)
		String addressFieldName = Util.getAddressField(name);
		int addressFieldAccess= Opcodes.ACC_FINAL | Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
		Object addressFieldValue;

		final boolean isFinal = (access & Opcodes.ACC_FINAL) != 0;
		final boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
		if(!isFinal){
			addressFieldValue = null;
			// include field if non final
			Field field = new Field(name, addressFieldName);
			fields.add(field);
			// also, if field is static, record it to indicate that there is at least one non final static field (i.e. a static field that's not a constant; by definition, constants do not need to be tracked)
			if(isStatic)
				staticField = name;
		}
		else {
			// TODO: I thought that we only gave -1 values to those synthetic fields which were true constants (both final AND static)
			// If this field is final mark with a negative address.
			addressFieldValue = -1L;
		}
		// TODO: I'm thinking that implementing FieldHolder may also be unnecessary, since all that is done is
		fieldsHolder.addField(addressFieldAccess, addressFieldName, Type.LONG_TYPE.getDescriptor(), addressFieldValue);

		return fieldVisitor;
	}

	@Override
	public MethodVisitor visitMethod(final int access, String name, String desc, String signature,
			String[] exceptions) {
		MethodVisitor originalMethod =  super.visitMethod(access, name, desc, signature, exceptions);

		// again, if the class is on exclude list, return without performing any transformations (TODO: e.g. synthetic methods...?)
		if(exclude)
			return originalMethod;

		// if method is native, create synthetic method that calls original method
		final boolean isNative = (access & Opcodes.ACC_NATIVE) != 0;
		if(isNative){
			createNativeMethod(access, name, desc, signature, exceptions);
			return originalMethod;
		}

		// if method is the class initialiser (aka static initialiser); TODO: work out exactly what's happening...
		if(name.equals("<clinit>")) {
			staticMethod = originalMethod; // TODO: seems almost pointless but used in a FieldHolder method further down
			visitclinit = true;

			if(isInterface){
				return originalMethod;
			}

			int fieldAccess = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
			fieldsHolder.addField(fieldAccess, StaticMethodTransformer.CLASS_BASE,
					Type.getDescriptor(Object.class), null);

			MethodVisitor staticMethodVisitor = fieldsHolder.getStaticInitialiserVisitor();
			return createStaticMethodTransformer(originalMethod, staticMethodVisitor);
		}
		Method newMethod = createNewMethod(name, desc);

		// Create a new duplicate SYNTHETIC method and remove the final marker if has one.
		MethodVisitor copyMethod =  super.visitMethod((access | Opcodes.ACC_SYNTHETIC) & ~Opcodes.ACC_FINAL, name, newMethod.getDescriptor(),
				signature, exceptions);

		return new MethodTransformer( originalMethod, copyMethod, className,
				access, name, desc, newMethod, fieldsHolder);
	}

	@Override
	public void visitEnd() {
		//Didn't see any static method till now, so creates one.
		if(!exclude){
			// add @Exclude annotation to transformed class (to stop (remote) possibility of getting transformed again!)
			super.visitAnnotation(EXCLUDE_DESC, false);

			// creates a new (moreorless empty) <clinit> in case we didn't see one already
			if(fields.size() > 0 && !visitclinit) {
				//TODO avoid creating new static method in case of external fields holder
				visitclinit = true;
				MethodVisitor method = visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
				method.visitCode();
				method.visitInsn(Opcodes.RETURN);
				method.visitMaxs(100, 100); // TODO set the right value
				method.visitEnd();

			}
			if(isEnum){ // Build a dummy ordinal() method
				MethodVisitor ordinalMethod =
					super.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, "ordinal", "(Lorg/deuce/transaction/Context;)I", null, null);
				ordinalMethod.visitCode();
				ordinalMethod.visitVarInsn(Opcodes.ALOAD, 0);
				ordinalMethod.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, "ordinal", "()I");
				ordinalMethod.visitInsn(Opcodes.IRETURN);
				ordinalMethod.visitMaxs(1, 2);
				ordinalMethod.visitEnd();
			}
		}
		super.visitEnd();
		fieldsHolder.close();
	}

	/**
	 * Creates synthetic method that calls into Context API to ensure irrevocable access
	 * before making a delegate call to the native method.
	 */
	private void createNativeMethod(int access, String name, String desc, String signature, String[] exceptions) {
		Method newMethod = createNewMethod(name, desc);
		MethodVisitor dupMethodWithContextArg =  super.visitMethod((access & ~Opcodes.ACC_NATIVE) | Opcodes.ACC_SYNTHETIC, name, newMethod.getDescriptor(),
				signature, exceptions);

		// start producing visit events on the new MethodVisitor that create the synthetic method
		dupMethodWithContextArg.visitCode();

		final boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;

		// call Context.onIrrevocableAccess (indirectly via ContextDelegator)
		int argumentsSize = Util.calcArgumentsSize(isStatic, newMethod);
		dupMethodWithContextArg.visitVarInsn(Opcodes.ALOAD, argumentsSize - 1); // load context; FIXME: if the native method is static and has no arguments, then argumentsSize - 1 would be -1 (I think)!
		dupMethodWithContextArg.visitMethodInsn(Opcodes.INVOKESTATIC, ContextDelegator.CONTEXT_DELEGATOR_INTERNAL,
				ContextDelegator.IRREVOCABLE_METHOD_NAME, ContextDelegator.IRREVOCABLE_METHOD_DESC);

		// load the arguments before calling the original method
		int place = 0; // place on the stack
		if(!isStatic){
			dupMethodWithContextArg.visitVarInsn(Opcodes.ALOAD, 0); // load this
			place = 1;
		}

		Type[] argumentTypes = newMethod.getArgumentTypes();
		for(int i=0 ; i<(argumentTypes.length-1) ; ++i){
			Type type = argumentTypes[i];
			dupMethodWithContextArg.visitVarInsn(type.getOpcode(Opcodes.ILOAD), place);
			place += type.getSize();
		}

		// call the original method
		dupMethodWithContextArg.visitMethodInsn(isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL, className, name, desc);
		TypeCodeResolver returnResolver = TypeCodeResolverFactory.getResolver(newMethod.getReturnType());
		if(returnResolver == null) {
			dupMethodWithContextArg.visitInsn(Opcodes.RETURN); // return;
		}else {
			dupMethodWithContextArg.visitInsn(returnResolver.returnCode());
		}
		dupMethodWithContextArg.visitMaxs(1, 1);

		// finish producing visit events on the new MethodVisitor that create the synthetic method
		dupMethodWithContextArg.visitEnd();
	}

	private StaticMethodTransformer createStaticMethodTransformer(MethodVisitor originalMethod, MethodVisitor staticMethod){
		return new StaticMethodTransformer( originalMethod, staticMethod, fields, staticField,
				className, fieldsHolder.getFieldsHolderName());
	}

	/**
	 * Takes existing method name and descriptor and returns a Method which is
	 * the same except for an additional argument for the Context object.
	 *
	 * @param name Name of method
	 * @param desc Method descriptor
	 * @return Method Same as method with an additional Context argument
	 */
	public static Method createNewMethod(String name, String desc) {
		Method method = new Method(name, desc);
		Type[] arguments = method.getArgumentTypes();

		Type[] newArguments = new Type[arguments.length + 1];
		System.arraycopy(arguments, 0, newArguments, 0, arguments.length);
		newArguments[newArguments.length - 1] = Context.CONTEXT_TYPE; // add as a constant

		return new Method(name, method.getReturnType(), newArguments);
	}


	/* ****************************************
	 * Implementation of FieldHolder interface
	 * ****************************************/

	@Override
	public void addField(int fieldAccess, String addressFieldName, String desc, Object value){
		super.visitField( fieldAccess, addressFieldName, desc, null, value);
	}

	@Override
	public void close(){
	}

	@Override
	public MethodVisitor getStaticInitialiserVisitor(){
		return staticMethod;
	}

	@Override
	public String getFieldsHolderName(){
		return className;
	}

	@Override
	public void visit(String superName) {
		//nothing to do
	}
}
