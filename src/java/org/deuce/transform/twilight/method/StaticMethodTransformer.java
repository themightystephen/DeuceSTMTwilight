package org.deuce.transform.twilight.method;

import java.util.List;

import org.deuce.objectweb.asm.MethodAdapter;
import org.deuce.objectweb.asm.MethodVisitor;
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
public class StaticMethodTransformer extends MethodAdapter {
	final static public String CLASS_BASE = "__CLASS_BASE__";

	private final List<Field> fields; // TODO: maybe rename to syntheticFields
	private final String className; // TODO: maybe rename to tranformedClassName????
	private final MethodVisitor staticMethod;
	private final String fieldsHolderName; // TODO: I think this is the extra field holder class that is generated (e.g. class BarDeuceFieldsHolder generated from original class Bar (in addition to the transformed version of Bar));
	// TODO: therefore probably rename it to fieldsHolderClassName
	private final String staticField; // may be null if

	public StaticMethodTransformer(MethodVisitor mv, MethodVisitor staticMethod, List<Field> fields,
			String staticField, String className, String fieldsHolderName) {
		super(mv);
		this.staticMethod = staticMethod;
		this.fields = fields;
		this.staticField = staticField;
		this.className = className;
		this.fieldsHolderName = fieldsHolderName;
	}

	@Override
	public void visitCode() {
		if(fields.size() > 0){
			// for every synthetic field, add instructions to initialise it
			for( Field field : fields) {
				staticMethod.visitLdcInsn(Type.getObjectType(className));
				staticMethod.visitLdcInsn(field.getFieldName());
				staticMethod.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getDeclaredField",
				"(Ljava/lang/String;)Ljava/lang/reflect/Field;");
				staticMethod.visitMethodInsn(INVOKESTATIC, "org/deuce/reflection/AddressUtil",
						"getAddress", "(Ljava/lang/reflect/Field;)J");
				staticMethod.visitFieldInsn(PUTSTATIC, fieldsHolderName, field.getFieldNameAddress(), "J");
			}

			// if there's at least one static field, also add instructions to initialise the __CLASS_BASE__ field
			if(staticField != null) {
				staticMethod.visitLdcInsn(Type.getObjectType(className));
				staticMethod.visitLdcInsn(staticField);
				staticMethod.visitMethodInsn(INVOKESTATIC, "org/deuce/reflection/AddressUtil",
						"staticFieldBase", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;");
				staticMethod.visitFieldInsn(PUTSTATIC, fieldsHolderName, CLASS_BASE, "Ljava/lang/Object;");
			}
		}
	}

	@Override
	public void visitEnd(){
		super.visitEnd();
		// TODO can we do it cleaner?
		if( super.mv != staticMethod)
			staticMethod.visitEnd();
	}
}
