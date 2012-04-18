package org.deuce.transaction.global;

import org.deuce.objectweb.asm.ClassVisitor;
import org.deuce.objectweb.asm.MethodVisitor;
import org.deuce.transform.commons.BaseClassTransformer;

public class ClassTransformer extends BaseClassTransformer {

	public ClassTransformer(ClassVisitor cv, String className) {
		super(cv, className); // FIXME: not sure that adding this cv parameter was a good idea. I might easily have broken something!
	}

	@Override
	public MethodVisitor visitMethod( int access, String name, String desc,
			String signature, String[] exceptions) {
		MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
		return new MethodTransformer( mv, access, name, desc, signature, exceptions, this);
	}

	public MethodVisitor createMethod( int access, String name, String desc,
			String signature, String[] exceptions) {
		return super.visitMethod(access, name, desc, signature, exceptions);
	}
}
