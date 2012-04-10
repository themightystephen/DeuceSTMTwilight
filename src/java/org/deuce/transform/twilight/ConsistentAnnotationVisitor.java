package org.deuce.transform.twilight;

import org.deuce.objectweb.asm.AnnotationVisitor;
import org.deuce.transform.twilight.method.ConsistentMethod;

public class ConsistentAnnotationVisitor implements AnnotationVisitor {

	private final ConsistentMethod method;
	private final AnnotationVisitor annotation;

	public ConsistentAnnotationVisitor(ConsistentMethod method, AnnotationVisitor annotation) {
		this.method = method;
		this.annotation = annotation;
	}

	public void visit(String name, Object value) {
		method.setRetries((Integer)value);
		annotation.visit(name, value);
	}

	public AnnotationVisitor visitAnnotation(String name, String desc) {
		return annotation.visitAnnotation(name, desc);
	}

	public AnnotationVisitor visitArray(String name) {
		return annotation.visitArray(name);
	}

	public void visitEnd() {
		annotation.visitEnd();
	}

	public void visitEnum(String name, String desc, String value) {
		annotation.visitEnum(name, desc, value);
	}

}
