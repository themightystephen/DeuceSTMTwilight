package org.deuce.transform.twilight;

import java.util.LinkedList;

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
import org.deuce.transform.twilight.method.MethodTransformer;
import org.deuce.transform.twilight.method.StaticInitialiserTransformer;

/**
 * Class-level visitor for performing transformations on bytecode to call into Twilight API.
 *
 * Transformations are applied to EVERY class which is not in the exclude list.
 *
 * The transformations performed by this visitor and the other MethodVisitors it invokes include:
 * - For every field, a constant is added (xxx__ADDRESS__) to keep the relative location of the field in the object for fast access (using the actual memory address I think...?)
 * - For every field, I AM NOT SURE IF TWO ACCESSORS ARE ADDED.
 * - For every class (?? or only ones with at least one static field), a constant is added (__CLASS_BASE__) to keep the reference to the
 * class definition and allow fast access to static fields
 * - Every non-@Atomic method has two copies of it:
 * 		a) the original, unchanged version (uninstrumented version) (called when not inside a transaction)
 * 		b) a transactional version which where direct field accesses are replaced with transactional field accesses (called when inside a transaction)
 * - Every @Atomic method has two copies of it:
 * 		a) an instrumented version which controls the start and end of a transaction, and calls the transactional version (below) inside the 'retry loop' (starts and ends the transaction)
 * 		b) a transactional version which where direct field accesses are replaced with transactional field accesses (called when inside a transaction)
 *   There is no original, uninstrumented version of @Atomic methods.
 * - For every instance field, class initialiser is appended (or added if none currently exists) to initialise the synthetic constants (i.e. initialising the xxx__ADDRESS__ synthetic fields)
 *
 * Optimisations relating to instrumentation:
 * - we do not instrument accesses to final fields as they cannot be modified after creation
 * - fields accessed as part of the constructor are ignored as they are not accessible by
 * concurrent threads until the constructor returns
 * - instead of generating accessor methods, we inline the code of getters and setters directly
 * in the transactional code
 *
 * @author Stephen Tuttlebee
 */
@Exclude
public class ClassTransformer extends BaseClassTransformer implements FieldsHolder{
	final private static String ENUM_DESC = Type.getInternalName(Enum.class);

	private boolean visitclinit = false;
	final private LinkedList<Field> syntheticAddressFields = new LinkedList<Field>();
	private String staticField = null; // non-null value indicates we need to add __CLASS_BASE__ to instrumented class (see StaticInitialiserTransformer)

	private boolean isInterface;
	private boolean isEnum;
	private MethodVisitor staticInitialiserVisitor;

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
		// when fieldsHolder is an ExternalFieldsHolder (i.e. offline instrumentation), visits of header of *new* class for holding fields
		fieldsHolder.visit(superName);

		// store useful information for later
		isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
		isEnum = ENUM_DESC.equals(superName);

		// visit class header as normal (TODO: we call visit on super here, ByteCodeVisitor, but all it does is something very simple that doesn't really need to be done there; it could just be done directly here)
		super.visit(version, access, name, signature, superName, interfaces);
	}

	/**
	 * Creates a new (synthetic) static final field for each existing field.
	 * The field will be statically initialized to hold the field address.
	 */
	@Override
	public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
		FieldVisitor fieldVisitor = super.visitField(access, name, desc, signature, value);

		// define synthetic "address" field as constant (i.e. static final)
		String addressFieldName = Util.getAddressField(name);
		int addressFieldAccess= Opcodes.ACC_FINAL | Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
		Object addressFieldValue; // if the (original) field is non-final, the value will be given in the class/static initialiser

		final boolean isFinal = (access & Opcodes.ACC_FINAL) != 0;
		final boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
		if(!isFinal){
			addressFieldValue = null;
			// include field if non final
			Field field = new Field(name, addressFieldName);
			syntheticAddressFields.add(field);
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
	public MethodVisitor visitMethod(final int access, String name, String desc, String signature, String[] exceptions) {
		MethodVisitor originalMethodVisitor =  super.visitMethod(access, name, desc, signature, exceptions);

		// if method is native, create synthetic method that calls original method
		final boolean isNative = (access & Opcodes.ACC_NATIVE) != 0;
		if(isNative){
			createNativeMethod(access, name, desc, signature, exceptions);
			return originalMethodVisitor;
		}

		// if method is the class initialiser (aka static initialiser); TODO: work out exactly what's happening...
		if(name.equals("<clinit>")) {
			staticInitialiserVisitor = originalMethodVisitor;
			visitclinit = true;

			if(isInterface){
				return originalMethodVisitor;
			}

			// existence of a class initialiser means we should add a __CLASS_BASE__ field to the class holding the synthetic fields [apparently...]
			int addressFieldAccess = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC; // *not* final to workaround JVM crash
			fieldsHolder.addField(addressFieldAccess, StaticInitialiserTransformer.CLASS_BASE, Type.getDescriptor(Object.class), null); // no value stored for __CLASS_BASE__ (hence why we make it non-final)

			MethodVisitor staticInitialiserVisitor = fieldsHolder.getStaticInitialiserVisitor();
			return new StaticInitialiserTransformer( originalMethodVisitor, staticInitialiserVisitor, syntheticAddressFields, staticField,
					className, fieldsHolder.getFieldsHolderName());
		}
		Method newMethod = createNewMethod(name, desc);

		// create a new duplicate SYNTHETIC method and remove the final marker if has one.
		// make sure the duplicate method won't be final to support special cases like enum and user hand crafted duplicated methods
		MethodVisitor copyMethod =  super.visitMethod((access | Opcodes.ACC_SYNTHETIC) & ~Opcodes.ACC_FINAL, name,
				newMethod.getDescriptor(), signature, exceptions);

		return new MethodTransformer(originalMethodVisitor, copyMethod, className,	access, name, desc, newMethod, fieldsHolder);
	}

	@Override
	public void visitEnd() {
		// add @Exclude annotation to transformed class (to stop possibility of transformed class getting transformed again!)
		super.visitAnnotation(IdentifyExcludedClassVisitor.EXCLUDE_DESC, false);

		// if no static initialiser existed before or created yet, create one (TODO: did he mean 'static method' here, or did he mean 'static initialiser method'?)
		// creates a new (essentially empty) <clinit> if we haven't see one already
		if(syntheticAddressFields.size() > 0 && !visitclinit) {
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

		super.visitEnd();
		fieldsHolder.close();
	}

	/**
	 * Creates synthetic method that calls into Context API to ensure irrevocable access
	 * before making a delegate call to the original native method.
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
	public void addField(int syntheticFieldAccess, String syntheticFieldName, String desc, Object value){
		super.visitField( syntheticFieldAccess, syntheticFieldName, desc, null, value);
	}

	@Override
	public void close(){
	}

	@Override
	public MethodVisitor getStaticInitialiserVisitor(){
		return staticInitialiserVisitor;
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
