package org.deuce.transform.asm;


import org.deuce.objectweb.asm.ClassAdapter;
import org.deuce.objectweb.asm.ClassReader;
import org.deuce.objectweb.asm.ClassWriter;
import org.deuce.objectweb.asm.MethodVisitor;
import org.deuce.objectweb.asm.commons.JSRInlinerAdapter;


/**
 * Provides a wrapper over {@link ClassAdapter}
 *
 * This class receives events corresponding to the bytecode structure [I think].
 *
 * @author Guy Korland
 * @since 1.0
 */
public class ByteCodeVisitor extends ClassAdapter{

	// visit a method
	@Override
	public MethodVisitor visitMethod(int access, String name, String desc,
			String signature, String[] exceptions) {
		MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
		return new JSRInlinerAdapter(mv, access, name, desc, signature, exceptions);
	}

	protected final String className;
	//The maximal bytecode version to transform.
	private int maximalversion = Integer.MAX_VALUE;

	public ByteCodeVisitor( String className) {

		super(new ClassWriter( ClassWriter.COMPUTE_MAXS));
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

	// hmmm, this looks like where the visiting truly starts; it matches up with what the paper on ASM transformations says happens on the first page.
	public byte[] visit( byte[] bytes){
		// reads the bytecode
		ClassReader cr = new ClassReader(bytes);
		// fire all visiting events corresponding to the bytecode structure; we pass 'this', which is a ClassAdapter which will handle the events
		cr.accept(this, ClassReader.EXPAND_FRAMES);
		return ((ClassWriter)super.cv).toByteArray();
	}


	public String getClassName() {
		return className;
	}

	private static class VersionException extends RuntimeException{
		private static final long serialVersionUID = 1L;
		public static VersionException INSTANCE = new VersionException();
	}

}
