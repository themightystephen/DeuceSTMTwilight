package org.deuce.transform.commons;

import org.deuce.objectweb.asm.MethodVisitor;

/**
 * Holds new fields for transformed classes.
 * @author guy
 * @since 1.1
 */
@Exclude //added by me
public interface FieldsHolder {

	void visit(String superName);
	MethodVisitor getStaticInitialiserVisitor();
	void addField(int syntheticFieldAccess, String syntheticFieldName, String desc, Object value);
	void close();
	String getFieldsHolderName();
}
