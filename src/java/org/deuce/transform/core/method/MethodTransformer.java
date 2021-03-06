package org.deuce.transform.core.method;

import java.util.HashMap;

import org.deuce.Atomic;
import org.deuce.Irrevocable;
import org.deuce.Unsafe;
import org.deuce.objectweb.asm.AnnotationVisitor;
import org.deuce.objectweb.asm.Attribute;
import org.deuce.objectweb.asm.Label;
import org.deuce.objectweb.asm.MethodVisitor;
import org.deuce.objectweb.asm.Opcodes;
import org.deuce.objectweb.asm.Type;
import org.deuce.objectweb.asm.commons.AnalyzerAdapter;
import org.deuce.objectweb.asm.commons.Method;
import org.deuce.transaction.ContextDelegator;
import org.deuce.transform.commons.FieldsHolder;
import org.deuce.transform.commons.util.Util;

import static org.deuce.objectweb.asm.Opcodes.*;

/**
 *
 *
 * @author Guy Korland
 */
public class MethodTransformer extends MethodVisitor {

	final static public String ATOMIC_DESCRIPTOR = Type.getDescriptor(Atomic.class);
	final static private String UNSAFE_DESCRIPTOR = Type.getDescriptor(Unsafe.class);
	final static private String IRREVOCABLE_DESCRIPTOR = Type.getDescriptor(Irrevocable.class);

	private MethodVisitor originalMethod;

	final private MethodVisitor originalCopyMethod;

	private MethodVisitor copyMethod;
	final private String className;
	final private String methodName;
	final private String descriptor; // original descriptor
	final private HashMap<Label, Label> labelMap = new HashMap<Label, Label>();
	final private boolean isStatic;
	private boolean isIrrevocable = false; // assume false
	final private Method newMethod;

	public MethodTransformer(MethodVisitor originalMethod, MethodVisitor copyMethod,
			String className, int access, String methodName, String descriptor, Method newMethod,
			FieldsHolder fieldsHolder) {
		super(Opcodes.ASM4);

		this.originalMethod = originalMethod;
		this.newMethod = newMethod;
		this.isStatic = (access & ACC_STATIC) != 0;
		this.originalCopyMethod = copyMethod; // save duplicate method without instrumentation.

		// The AnalyzerAdapter delegates the call to the DuplicateMethod, while the DuplicateMethod uses
		// the analyzer for stack state in the original method.
		DuplicateMethod duplicateMethod = new DuplicateMethod( copyMethod, isStatic, newMethod, fieldsHolder);
		AnalyzerAdapter analyzerAdapter = new AnalyzerAdapter( className, access, methodName, descriptor, duplicateMethod);
		duplicateMethod.setAnalyzer( analyzerAdapter);

		this.copyMethod = analyzerAdapter;
		this.className = className;
		this.methodName = methodName;
		this.descriptor = descriptor;
	}

	/**
	 * This visit method is very important since it determines what kind of method
	 * transformations are required for the current method.
	 */
	@Override
	public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
		// if marked as @Atomic, add additional visitor to chain which generates transactional version of original method
		// FIXME we might saw other annotations before and we need to put it on the new AtomicMethod
		// need to create an atomic method from the original method
		// [I think] second condition necessary to ensure we do not atomicize more than once when programmer places multiple @Atomic annotations on the same method
		if(ATOMIC_DESCRIPTOR.equals(desc) && !(originalMethod instanceof AtomicMethod))
			originalMethod = new AtomicMethod(originalMethod, className, methodName, descriptor, newMethod, isStatic);

		// if marked as @Unsafe, just duplicate the method as is
		if(UNSAFE_DESCRIPTOR.equals(desc))
			copyMethod = originalCopyMethod;

		// if marked as @Irrevocable, no need to instrument call
		if(IRREVOCABLE_DESCRIPTOR.equals(desc)){
			copyMethod = originalCopyMethod;
			isIrrevocable = true;
		}

		// if marked with a JUnit annotation, ...
		if(desc.contains("org/junit")) // TODO find another way
			return originalMethod.visitAnnotation(desc, visible);

		return new MethodAnnotationVisitor( originalMethod.visitAnnotation(desc, visible),
				copyMethod.visitAnnotation(desc, visible));
	}

	@Override
	public AnnotationVisitor visitAnnotationDefault() {
		return new MethodAnnotationVisitor( originalMethod.visitAnnotationDefault(),
				copyMethod.visitAnnotationDefault());
	}

	@Override
	public void visitAttribute(Attribute attr) {
		originalMethod.visitAttribute(attr);
		copyMethod.visitAttribute(attr);
	}

	@Override
	public void visitCode() {
		originalMethod.visitCode();
		copyMethod.visitCode();

		// NOTE: isIrrevocable has been set when visiting annotation of method, before code of method begins
		if(isIrrevocable){ // call onIrrevocableAccess
			int argumentsSize = Util.calcArgumentsSize(isStatic, newMethod);
			copyMethod.visitVarInsn(Opcodes.ALOAD, argumentsSize - 1); // load context
			copyMethod.visitMethodInsn( Opcodes.INVOKESTATIC, ContextDelegator.CONTEXT_DELEGATOR_INTERNAL,
					ContextDelegator.IRREVOCABLE_METHOD_NAME, ContextDelegator.IRREVOCABLE_METHOD_DESC);
		}

	}

	@Override
	public void visitEnd() {
		originalMethod.visitEnd();
		copyMethod.visitEnd();
	}

	@Override
	public void visitFieldInsn(int opcode, String owner, String name, String desc) {
		originalMethod.visitFieldInsn(opcode, owner, name, desc);
		copyMethod.visitFieldInsn(opcode, owner, name, desc);
	}

	@Override
	public void visitFrame(int type, int local, Object[] local2, int stack, Object[] stack2) {
		originalMethod.visitFrame(type, local, local2, stack, stack2);
		copyMethod.visitFrame(type, local, local2, stack, stack2);
	}

	@Override
	public void visitIincInsn(int var, int increment) {
		originalMethod.visitIincInsn(var, increment);
		copyMethod.visitIincInsn(var, increment);
	}

	@Override
	public void visitInsn(int opcode) {
		originalMethod.visitInsn(opcode);
		copyMethod.visitInsn(opcode);
	}

	@Override
	public void visitIntInsn(int opcode, int operand) {
		originalMethod.visitIntInsn(opcode, operand);
		copyMethod.visitIntInsn(opcode, operand);
	}

	@Override
	public void visitJumpInsn(int opcode, Label label) {
		originalMethod.visitJumpInsn(opcode, label);
		copyMethod.visitJumpInsn(opcode, getLabel(label));
	}

	@Override
	public void visitLabel(Label label) {
		originalMethod.visitLabel(label);
		copyMethod.visitLabel(getLabel(label));
	}

	@Override
	public void visitLdcInsn(Object cst) {
		originalMethod.visitLdcInsn(cst);
		copyMethod.visitLdcInsn(cst);
	}

	@Override
	public void visitLineNumber(int line, Label start) {
		originalMethod.visitLineNumber(line, start);
		copyMethod.visitLineNumber(line, getLabel(start));
	}

	@Override
	public void visitLocalVariable(String name, String desc, String signature, Label start,
			Label end, int index) {
		originalMethod.visitLocalVariable(name, desc, signature, start, end, index);
		copyMethod.visitLocalVariable(name, desc, signature, getLabel(start), getLabel(end), index);
	}

	@Override
	public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
		originalMethod.visitLookupSwitchInsn(dflt, keys, labels);
		copyMethod.visitLookupSwitchInsn( getLabel(dflt), keys, getCopyLabels(labels));
	}

	@Override
	public void visitMaxs(int maxStack, int maxLocals) {
		originalMethod.visitMaxs(maxStack, maxLocals);
		copyMethod.visitMaxs(maxStack, maxLocals);
	}

	@Override
	public void visitMethodInsn(int opcode, String owner, String name, String desc) {
		originalMethod.visitMethodInsn(opcode, owner, name, desc);
		copyMethod.visitMethodInsn(opcode, owner, name, desc);
	}

	@Override
	public void visitMultiANewArrayInsn(String desc, int dims) {
		originalMethod.visitMultiANewArrayInsn(desc, dims);
		copyMethod.visitMultiANewArrayInsn(desc, dims);

	}

	@Override
	public AnnotationVisitor visitParameterAnnotation(int parameter, String desc, boolean visible) {
		return new MethodAnnotationVisitor( originalMethod.visitParameterAnnotation(parameter, desc, visible),
				copyMethod.visitParameterAnnotation(parameter, desc, visible));
	}

	@Override
	public void visitTableSwitchInsn(int min, int max, Label dflt, Label[] labels) {
		originalMethod.visitTableSwitchInsn(min, max, dflt, labels);
		copyMethod.visitTableSwitchInsn(min, max, getLabel(dflt), getCopyLabels(labels));
	}

	@Override
	public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
		originalMethod.visitTryCatchBlock(start, end, handler, type);
		copyMethod.visitTryCatchBlock(getLabel(start), getLabel(end), getLabel(handler), type);
	}

	@Override
	public void visitTypeInsn(int opcode, String type) {
		originalMethod.visitTypeInsn(opcode, type);
		copyMethod.visitTypeInsn(opcode, type);
	}

	@Override
	public void visitVarInsn(int opcode, int var) {
		originalMethod.visitVarInsn(opcode, var);
		copyMethod.visitVarInsn(opcode, var);
	}

	/* ********************
	 * HELPER METHODS
	 * ********************/

	private Label[] getCopyLabels(Label[] labels) {
		Label[] copyLabels = new Label[ labels.length];
		for( int i=0; i<labels.length ;++i) {
			copyLabels[i] = getLabel(labels[i]);
		}
		return copyLabels;
	}

	private Label getLabel( Label label){
		Label duplicateLabel = labelMap.get( label);
		if( duplicateLabel == null) {
			duplicateLabel = new Label();
			labelMap.put(label, duplicateLabel);
		}
		return duplicateLabel;
	}
}
