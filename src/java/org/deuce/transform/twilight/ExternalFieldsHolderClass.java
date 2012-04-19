package org.deuce.transform.twilight;

import org.deuce.objectweb.asm.ClassWriter;
import org.deuce.objectweb.asm.MethodVisitor;
import org.deuce.objectweb.asm.Opcodes;
import org.deuce.transform.twilight.ClassTransformer;
import org.deuce.transform.commons.ExcludeIncludeStore;
import org.deuce.transform.commons.FieldsHolder;

/**
 * Creates a class to hold the fields address, used by the offline instrumentation.
 *
 * TODO: haven't worked out precise purpose of this yet...and exactly how and why transformation is
 * different for online and offline instrumentation.
 *
 * Okay, offline instrumentation decides to put synthetic fields into SEPARATE class xxxDeuceFieldsHolder
 * for a class xxx.
 *
 * @author guy
 * @since 1.1
 */
public class ExternalFieldsHolderClass implements FieldsHolder {

	final static private String FIELDS_HOLDER_CLASS_NAME_SUFFIX = "DeuceFieldsHolder";

	final private ClassWriter classWriter;
	final private String className;
	private ExternalStaticInitialiserVisitor extStaticInitVisitor;

	public ExternalFieldsHolderClass(String originalClassName){
		this.className = originalClassName + FIELDS_HOLDER_CLASS_NAME_SUFFIX;
		this.classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
	}

	/**
	 * Initiates the creation of the separate class (bytecode) to hold the synthetic fields.
	 */
	public void visit(String superName){
		String superFieldHolder = ExcludeIncludeStore.exclude(superName) ? "java/lang/Object" : getFieldsHolderName(superName);
		classWriter.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER,
				this.className, null, superFieldHolder, null);
		classWriter.visitAnnotation(IdentifyExcludedClassVisitor.EXCLUDE_DESC, false);
		extStaticInitVisitor = new ExternalStaticInitialiserVisitor(classWriter.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null));
		extStaticInitVisitor.visitCode();
	}

	@Override
	public void addField(int syntheticFieldAccess, String syntheticFieldName, String desc,
			Object value) {
		classWriter.visitField(syntheticFieldAccess, syntheticFieldName, desc, null, value);
	}

	@Override
	public void close(){
		extStaticInitVisitor.visitEnd();
		classWriter.visitEnd();
	}

	@Override
	public MethodVisitor getStaticInitialiserVisitor(){
		return extStaticInitVisitor;
	}

	/**
	 * Name of original owner class concatenated with predefined 'DeuceFieldsHolder' String
	 */
	@Override
	public String getFieldsHolderName() {
		return className;
	}

	public byte[] getBytecode(){
		return classWriter.toByteArray();
	}

	/**
	 * Given the name of any class, what would be the name of its corresponding fields
	 * holder class.
	 *
	 * @param className Name of class
	 * @return Name of corresponding fields holder class
	 */
	public static String getFieldsHolderName(String className) {
		return className + FIELDS_HOLDER_CLASS_NAME_SUFFIX;
	}

	/**
	* A wrapper method that is used to close the new <clinit>.
	*/
	private static class ExternalStaticInitialiserVisitor extends MethodVisitor {

		private boolean ended = false;

		public ExternalStaticInitialiserVisitor(MethodVisitor mv) {
			super(Opcodes.ASM4,mv);
		}

		@Override
		public void visitEnd(){
			if(ended)
				return;

			ended = true;
			super.visitInsn(Opcodes.RETURN);
			super.visitMaxs(1, 1); // Dummy call
			super.visitEnd();
		}
	}
}
