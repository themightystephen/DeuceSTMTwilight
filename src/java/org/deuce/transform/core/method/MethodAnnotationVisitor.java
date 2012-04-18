package org.deuce.transform.core.method;

import org.deuce.objectweb.asm.AnnotationVisitor;
import org.deuce.objectweb.asm.Opcodes;

public class MethodAnnotationVisitor extends AnnotationVisitor {

	private final AnnotationVisitor originalVisitor;
	private final AnnotationVisitor copyVisitor;

	public MethodAnnotationVisitor( AnnotationVisitor originalVisitor, AnnotationVisitor copyVisitor) {
		super(Opcodes.ASM4);
		this.originalVisitor = originalVisitor;
		this.copyVisitor = copyVisitor;
	}
	public void visit(String name, Object value) {
		originalVisitor.visit(name, value);
		copyVisitor.visit(name, value);
	}

	public AnnotationVisitor visitAnnotation(String name, String desc) {
		// TODO: is this a copy-and-paste error here??? Should we not be calling visitAnnotation() on the originalVisitor and copyVisitors rather than visitArray?
		return new MethodAnnotationVisitor( originalVisitor.visitArray(name),
				copyVisitor.visitArray(name));
	}

	public AnnotationVisitor visitArray(String name) {
		return new MethodAnnotationVisitor( originalVisitor.visitArray(name),
				copyVisitor.visitArray(name));
	}

	public void visitEnd() {
		originalVisitor.visitEnd();
		copyVisitor.visitEnd();
	}

	public void visitEnum(String name, String desc, String value) {
		originalVisitor.visitEnum(name, desc, value);
		copyVisitor.visitEnum(name, desc, value);
	}
}
