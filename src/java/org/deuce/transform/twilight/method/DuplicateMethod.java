package org.deuce.transform.twilight.method;

import org.deuce.objectweb.asm.Label;
import org.deuce.objectweb.asm.MethodVisitor;
import org.deuce.objectweb.asm.Opcodes;
import org.deuce.objectweb.asm.Type;
import org.deuce.objectweb.asm.commons.AnalyzerAdapter;
import org.deuce.objectweb.asm.commons.Method;
import org.deuce.transaction.Context;
import org.deuce.transaction.ContextDelegator;
import org.deuce.transform.twilight.ClassTransformer;
import org.deuce.transform.commons.FieldsHolder;
import org.deuce.transform.commons.util.Util;
import org.deuce.transform.commons.ExcludeIncludeStore;
import static org.deuce.objectweb.asm.Opcodes.*;

/**
 * Responsible for creating the mirror version for the original
 * method that includes instrumentation.
 *
 * This duplication should occur for ALL methods, whether they are annotated with
 * Atomic or not.
 *
 * i.e. all direct field accesses are transformed into call into Context API (via the
 * ContextDelegator class). It also deals with accesses to array elements equally
 * well too. It also changes method calls to call transactional versions of methods
 * (where possible).
 *
 * [I think this will be unchanged -- because the key things it does is to change
 * any method calls to invoke the transactional methods of the owning objects (where
 * possible; i.e. where the owning object's class is not in the exclude list) and
 * to replace field accesses with appropriate method calls to the Context API
 * (indirectly via ContextDelegator)].
 *
 * @author Guy Korland
 */
public class DuplicateMethod extends MethodVisitor {
	final static public String LOCAL_VARIABLE_NAME = "__transactionContext__";
	private static final String TWILIGHT_API_CLASS = "org/deuce/transaction/Twilight"; // to stop instrumentation of API method calls

	private final int argumentsSize;
	private final FieldsHolder fieldsHolder;

	private Label firstLabel;
	private Label lastLabel;
	private boolean addContextToTable = false;
	private AnalyzerAdapter analyzerAdapter;

	public DuplicateMethod(MethodVisitor mv, boolean isstatic, Method newMethod, FieldsHolder fieldsHolder) {
		super(Opcodes.ASM4,mv);
		this.fieldsHolder = fieldsHolder;
		this.argumentsSize = Util.calcArgumentsSize( isstatic, newMethod);
	}

	public void setAnalyzer(AnalyzerAdapter analyzerAdapter) {
		this.analyzerAdapter = analyzerAdapter;
	}

	/**
	 * Modifies method calls inside this transactional/duplicate method to call the transactional /
	 * duplicate method of the owning class EXCEPT in the hopefully non-existent case where the owning
	 * class of the method being called is on the exclude list (if this is the case, then the
	 * programmer should not have excluded the class or should have ensured that the class was not used
	 * inside a transaction).
	 */
	@Override
	public void visitMethodInsn(int opcode, String owner, String name, String desc) {
		// NOTE: do NOT just put org/deuce/transaction/Twilight into the default exclude set, since that set could be overriden by the user at the command-line
		// NOTE: even if I get around the problem of the default being overriden, I still can't just add Twilight class into exclude tree in ExcludeIncludeStore because it could get re-added by the user using the 'include set' option at the command-line

		// if in method's owning class is in the user-defined 'exclude set' OR is a Twilight API call; TODO: think about whether we should have both a pre-defined exclude set of things we KNOW should always be excluded (such as Twilight class and the whole deuce framework for that matter) AND a user-defined set that goes on top of that...I suppose we could allow the user to have deuce package in their 'include set' if they want to. It would be an odd thing to do but we should still let them do it...
		if(ExcludeIncludeStore.exclude(owner) || owner.equals(TWILIGHT_API_CLASS)) {
			// call uninstrumented method
			super.visitMethodInsn(opcode, owner, name, desc); // ... = foo( ...
		}
		else {
			// call duplicate synthetic method instead of uninstrumented method (i.e. the synthetic method requires a Context argument)
			super.visitVarInsn(ALOAD, argumentsSize - 1); // load context
			Method newMethod = ClassTransformer.createNewMethod(name, desc);
			super.visitMethodInsn(opcode, owner, name, newMethod.getDescriptor()); // ... = foo( ...
		}
	}


	/**
	 * When we see a field access, replace it with appropriate calls to the Context (via the
	 * ContextDelegator).
	 */
	@Override
	public void visitFieldInsn(int opcode, String owner, String name, String desc) {
		if(ExcludeIncludeStore.exclude(owner) || name.contains("$")) { // Syntactic TODO remove this limitation
			super.visitFieldInsn(opcode, owner, name, desc); // ... = foo( ...
			return;
		}

		String fieldsHolderName = fieldsHolder.getFieldsHolderName();
		mv.visitFieldInsn(GETSTATIC, fieldsHolderName, Util.getAddressField(name), "J");
		Label l1 = new Label();
		mv.visitInsn(LCONST_0);
		mv.visitInsn(LCMP);
		mv.visitJumpInsn(IFGE, l1);
		super.visitFieldInsn(opcode, owner, name, desc);
		Label l2 = new Label();
		mv.visitJumpInsn(GOTO, l2);
		mv.visitLabel(l1);

		final Type type = Type.getType(desc);
		switch( opcode) {
		case GETFIELD:  //	ALOAD 0: this (stack status)

			addBeforeReadCall(fieldsHolderName, name);

			super.visitInsn(DUP);
			super.visitFieldInsn(opcode, owner, name, desc);
			super.visitFieldInsn(GETSTATIC, fieldsHolderName, Util.getAddressField(name) , "J");
			super.visitVarInsn(ALOAD, argumentsSize - 1); // load context
			super.visitMethodInsn(INVOKESTATIC, ContextDelegator.CONTEXT_DELEGATOR_INTERNAL,
					ContextDelegator.READ_METHOD_NAME, ContextDelegator.getReadMethodDesc(type));

			if(type.getSort() >= Type.ARRAY) // non primitive
				super.visitTypeInsn(CHECKCAST, Type.getType(desc).getInternalName());
			break;
		case PUTFIELD:
			super.visitFieldInsn(GETSTATIC, fieldsHolderName, Util.getAddressField(name) , "J");
			super.visitVarInsn(ALOAD, argumentsSize - 1); // load context
			super.visitMethodInsn(INVOKESTATIC, ContextDelegator.CONTEXT_DELEGATOR_INTERNAL,
					ContextDelegator.WRITE_METHOD_NAME, ContextDelegator.getWriteMethodDesc(type));
			break;
		case GETSTATIC: // check support for static fields
			super.visitFieldInsn(GETSTATIC, fieldsHolderName,
					StaticInitialiserTransformer.CLASS_BASE, "Ljava/lang/Object;");

			addBeforeReadCall(fieldsHolderName, name);

			super.visitFieldInsn(opcode, owner, name, desc);
			super.visitFieldInsn(GETSTATIC, fieldsHolderName, Util.getAddressField(name) , "J");
			super.visitVarInsn(ALOAD, argumentsSize - 1); // load context
			super.visitMethodInsn( INVOKESTATIC, ContextDelegator.CONTEXT_DELEGATOR_INTERNAL,
					ContextDelegator.READ_METHOD_NAME, ContextDelegator.getReadMethodDesc(type));

			if( type.getSort() >= Type.ARRAY) // non primitive
				super.visitTypeInsn( CHECKCAST, Type.getType(desc).getInternalName());
			break;
		case PUTSTATIC:
			super.visitFieldInsn(GETSTATIC, fieldsHolderName,
					StaticInitialiserTransformer.CLASS_BASE, "Ljava/lang/Object;");
			super.visitFieldInsn( GETSTATIC, fieldsHolderName, Util.getAddressField(name) , "J");
			super.visitVarInsn(ALOAD, argumentsSize - 1); // load context
			super.visitMethodInsn( INVOKESTATIC, ContextDelegator.CONTEXT_DELEGATOR_INTERNAL,
					ContextDelegator.STATIC_WRITE_METHOD_NAME, ContextDelegator.getStaticWriteMethodDesc(type));
			break;
		default:
			super.visitFieldInsn(opcode, owner, name, desc);
		}
		mv.visitLabel(l2);
	}

	private void addBeforeReadCall(String owner, String name) {
		super.visitInsn(DUP);
		super.visitFieldInsn( GETSTATIC, owner, Util.getAddressField(name) , "J");
		super.visitVarInsn(ALOAD, argumentsSize - 1); // load context
		super.visitMethodInsn( INVOKESTATIC, ContextDelegator.CONTEXT_DELEGATOR_INTERNAL,
				ContextDelegator.BEFORE_READ_METHOD_NAME, ContextDelegator.BEFORE_READ_METHOD_DESC);
	}

	/**
	 * Adds for each array cell visited a call to the context
	 */
	@Override
	public void visitInsn(int opcode) {
		boolean load = false;
		boolean store = false;
		String desc = null;
		String arrayMemeberType = null;
		switch( opcode) {

		case AALOAD:
			// handle Object[] arrays type, the type before the last is the array.
			// The substring removes the '[' from the array type
			String arrayType =
				(String)this.analyzerAdapter.stack.get(this.analyzerAdapter.stack.size() - 2);
			arrayMemeberType = getArrayMemberType( arrayType);

			desc = ContextDelegator.READ_ARRAY_METHOD_OBJ_DESC;
			load = true;
			break;
		case BALOAD:
			desc = ContextDelegator.READ_ARRAY_METHOD_BYTE_DESC;
			load = true;
			break;
		case CALOAD:
			desc = ContextDelegator.READ_ARRAY_METHOD_CHAR_DESC;
			load = true;
			break;
		case SALOAD:
			desc = ContextDelegator.READ_ARRAY_METHOD_SHORT_DESC;
			load = true;
			break;
		case IALOAD:
			desc = ContextDelegator.READ_ARRAY_METHOD_INT_DESC;
			load = true;
			break;
		case LALOAD:
			desc = ContextDelegator.READ_ARRAY_METHOD_LONG_DESC;
			load = true;
			break;
		case FALOAD:
			desc = ContextDelegator.READ_ARRAY_METHOD_FLOAT_DESC;
			load = true;
			break;
		case DALOAD:
			desc = ContextDelegator.READ_ARRAY_METHOD_DOUBLE_DESC;
			load = true;
			break;

		case AASTORE:
			desc = ContextDelegator.WRITE_ARRAY_METHOD_OBJ_DESC;
			store = true;
			break;
		case BASTORE:
			desc = ContextDelegator.WRITE_ARRAY_METHOD_BYTE_DESC;
			store = true;
			break;
		case CASTORE:
			desc = ContextDelegator.WRITE_ARRAY_METHOD_CHAR_DESC;
			store = true;
			break;
		case SASTORE:
			desc = ContextDelegator.WRITE_ARRAY_METHOD_SHORT_DESC;
			store = true;
			break;
		case IASTORE:
			desc = ContextDelegator.WRITE_ARRAY_METHOD_INT_DESC;
			store = true;
			break;
		case LASTORE:
			desc = ContextDelegator.WRITE_ARRAY_METHOD_LONG_DESC;
			store = true;
			break;
		case FASTORE:
			desc = ContextDelegator.WRITE_ARRAY_METHOD_FLOAT_DESC;
			store = true;
			break;
		case DASTORE:
			desc = ContextDelegator.WRITE_ARRAY_METHOD_DOUBLE_DESC;
			store = true;
			break;
		}

		if( load)
		{
			super.visitVarInsn(ALOAD, argumentsSize - 1); // load context
			super.visitMethodInsn( INVOKESTATIC, ContextDelegator.CONTEXT_DELEGATOR_INTERNAL,
					ContextDelegator.READ_ARR_METHOD_NAME, desc);

			if( opcode == AALOAD){ // non primitive array need cast
				super.visitTypeInsn( CHECKCAST, arrayMemeberType);
			}
		}
		else if( store)
		{
			super.visitVarInsn(ALOAD, argumentsSize - 1); // load context
			super.visitMethodInsn( INVOKESTATIC, ContextDelegator.CONTEXT_DELEGATOR_INTERNAL,
					ContextDelegator.WRITE_ARR_METHOD_NAME, desc);
		}
		else{
			super.visitInsn(opcode);
		}
	}

	@Override
	public void visitIincInsn(int var, int increment) {
		super.visitIincInsn( newIndex(var), increment); // increase index due to context
	}

	@Override
	public void visitLabel(Label label) {
		if( firstLabel == null)
			firstLabel = label;
		lastLabel = label;
		super.visitLabel(label);
	}

	@Override
	public void visitLocalVariable(String name, String desc, String signature, Label start,
			Label end, int index) {
		if( this.argumentsSize >  index + 1) // argument
		{
			super.visitLocalVariable(name, desc, signature, start, end, index); // non static method has this
			return;
		}
		// add context as last argument
		// the first local variable and was never added before
		if( this.argumentsSize ==  index + 1 && !addContextToTable)
		{
			addContextToTable = true;
			super.visitLocalVariable(LOCAL_VARIABLE_NAME, Context.CONTEXT_DESC, null,
					firstLabel, lastLabel, index);
		}

		// increase all the locals index
		super.visitLocalVariable(name, desc, signature, start, end, index + 1);
	}

	@Override
	public void visitMaxs(int maxStack, int maxLocals) {
		super.visitMaxs(maxStack + 3, maxLocals + 1);
	}

	@Override
	public void visitVarInsn(int opcode, int var) {
		// increase the local variable index by 1
		super.visitVarInsn(opcode, newIndex(var));
	}

	/**
	 * Calculate the new local index according to its position.
	 * If it's not a function argument (local variable) its index increased by 1.
	 * @param currIndex current index
	 * @return new index
	 */
	private int newIndex( int currIndex){
		return currIndex + 1 < this.argumentsSize ? currIndex : currIndex + 1;
	}

	private String getArrayMemberType( String arrayType){
		if( arrayType.charAt(arrayType.length() - 1) == ';' && // primitive array
				arrayType.charAt(1) != '[' ) // array of arrays
			return arrayType.substring(2, arrayType.length()-1);

		return arrayType.substring(1, arrayType.length()); // array of Objects
	}
}
