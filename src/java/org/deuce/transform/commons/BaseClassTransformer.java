package org.deuce.transform.commons;

import org.deuce.objectweb.asm.ClassAdapter;
import org.deuce.objectweb.asm.ClassVisitor;
import org.deuce.objectweb.asm.MethodVisitor;
import org.deuce.objectweb.asm.commons.JSRInlinerAdapter;


/**
 * <p>Provides a wrapper over {@link ClassAdapter}.</p>
 *
 * <p>Base transformation visitor for the ClassTransformer subclasses in the core and twilight
 * packages. Contains the very basic common transformations required, and stores class name...
 * TODO: I don't think I will remove this class...I was thinking about it but I'm inclining to
 * keep it but maybe improve it where possible.</p>
 *
 * @author Guy Korland
 * @since 1.0
 */
public class BaseClassTransformer extends ClassAdapter {
	protected final String className;
	private int maximalversion = Integer.MAX_VALUE; // the maximal bytecode version to transform

	/**
	 * Constructor.
	 *
	 * @param cv
	 * @param className
	 */
	public BaseClassTransformer(ClassVisitor cv, String className) {
		super(cv);
		this.className = className;
	}

	// visit header of the class
	@Override
	public void visit(final int version, final int access, final String name,
			final String signature, final String superName, final String[] interfaces) {
		if(version > maximalversion) // version higher than allowed
			throw VersionException.INSTANCE;
		super.visit(version, access, name, signature, superName, interfaces);
	}

	@Override
	public MethodVisitor visitMethod(int access, String name, String desc,
			String signature, String[] exceptions) {
		MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
		return new JSRInlinerAdapter(mv, access, name, desc, signature, exceptions);
	}

	public String getClassName() {
		return className;
	}

	private static class VersionException extends RuntimeException{
		private static final long serialVersionUID = 1L;
		public static VersionException INSTANCE = new VersionException();
	}

}
