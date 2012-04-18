package org.deuce.transform.twilight.method;

import java.util.List;

import org.deuce.objectweb.asm.MethodVisitor;
import org.deuce.objectweb.asm.Opcodes;
import org.deuce.objectweb.asm.Type;
import org.deuce.transform.commons.Field;

import static org.deuce.objectweb.asm.Opcodes.*;

/**
 * I'm thinking this transformer is actually only used for transforming the code of the
 * class/static initialiser and not any other static methods. Plus, the the content of
 * visitCode() seems to indicate that too.
 *
 * If I do confirm this to be the case, then I will rename the class to something more
 * accurate, such as ClassInitialiserTransformer/ClassInitialiserAdapter.
 *
 * @author Guy
 */
public class StaticInitialiserTransformer extends MethodVisitor {
	final static public String CLASS_BASE = "__CLASS_BASE__";

	private final List<Field> syntheticFields;
	private final String className; // class being transformed
	private final MethodVisitor staticInitialiserMethod;
	private final String fieldsHolderName; // TODO: I think this is the extra field holder class that is generated (e.g. class BarDeuceFieldsHolder generated from original class Bar (in addition to the transformed version of Bar));
	// TODO: therefore probably rename it to fieldsHolderClassName
	private final String aNonSyntheticStaticField; // holds name of some non-synthetic static field of the class being transformed; null if there are no such fields

	/**
	 *
	 *
	 * @param mv
	 * @param staticInitialiserMethod
	 * @param syntheticFields
	 * @param aNonSyntheticStaticField
	 * @param className
	 * @param fieldsHolderName
	 */
	public StaticInitialiserTransformer(MethodVisitor mv, MethodVisitor staticInitialiserMethod, List<Field> syntheticFields,
			String aNonSyntheticStaticField, String className, String fieldsHolderName) {
		super(Opcodes.ASM4,mv);
		this.staticInitialiserMethod = staticInitialiserMethod;
		this.syntheticFields = syntheticFields;
		this.aNonSyntheticStaticField = aNonSyntheticStaticField;
		this.className = className;
		this.fieldsHolderName = fieldsHolderName;
	}

	@Override
	public void visitCode() {
		if(syntheticFields.size() > 0){
			// for every synthetic field, add instructions to initialise it
			for( Field field : syntheticFields) {
				staticInitialiserMethod.visitLdcInsn(Type.getObjectType(className));
				staticInitialiserMethod.visitLdcInsn(field.getFieldName());
				staticInitialiserMethod.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getDeclaredField",
				"(Ljava/lang/String;)Ljava/lang/reflect/Field;");
				staticInitialiserMethod.visitMethodInsn(INVOKESTATIC, "org/deuce/reflection/AddressUtil",
						"getAddress", "(Ljava/lang/reflect/Field;)J");
				staticInitialiserMethod.visitFieldInsn(PUTSTATIC, fieldsHolderName, field.getFieldNameAddress(), "J");
			}

			// if there's at least one (original, non-synthetic) static field, also add instructions to initialise the __CLASS_BASE__ field (using the name of some static field)
			if(aNonSyntheticStaticField != null) {
				staticInitialiserMethod.visitLdcInsn(Type.getObjectType(className));
				staticInitialiserMethod.visitLdcInsn(aNonSyntheticStaticField);
				staticInitialiserMethod.visitMethodInsn(INVOKESTATIC, "org/deuce/reflection/AddressUtil",
						"staticFieldBase", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;");
				staticInitialiserMethod.visitFieldInsn(PUTSTATIC, fieldsHolderName, CLASS_BASE, "Ljava/lang/Object;");
			}
		}
	}

	@Override
	public void visitEnd(){
		super.visitEnd();
		// TODO can we do it cleaner?
		if( super.mv != staticInitialiserMethod)
			staticInitialiserMethod.visitEnd();
	}
}
