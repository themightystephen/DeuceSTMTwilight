package org.deuce.transform.twilight;

import java.lang.annotation.Annotation;

import org.deuce.objectweb.asm.AnnotationVisitor;
import org.deuce.objectweb.asm.ClassVisitor;
import org.deuce.objectweb.asm.Type;
import org.deuce.transform.commons.Exclude;

/**
 * A visitor which indicates whether a class is marked with @Exclude annotation or not.
 * This visitor is an event consumer, a visitor that is at the end of a chain.
 *
 * It is expected that a ClassReader will be the source of events.
 *
 * @author stephen
 *
 */
public class IdentifyExcludedClassVisitor extends ClassVisitor {
	final static public String EXCLUDE_DESC = Type.getDescriptor(Exclude.class); // TODO: move this constant into constants file (and do this for other constants)
	final static private String ANNOTATION_DESC = Type.getInternalName(Annotation.class);
	private boolean excluded;

	public IdentifyExcludedClassVisitor(int api) {
		super(api);
		this.excluded = false;
	}

	public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
		// exclude 'class' if it is actually an Annotation type (http://docs.oracle.com/javase/1.5.0/docs/api/java/lang/annotation/Annotation.html)
		for(String inter : interfaces){
			if( inter.equals(ANNOTATION_DESC)){
				excluded = true;
				break;
			}
		}
	}

	/**
	 * Check if class is marked with @Exclude annotation. If so, add it to Set of excluded classes.
	 */
	@Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
    	if(desc.equals(EXCLUDE_DESC)) {
    		excluded = true;
        }
    	return null;
    }

	/**
	 * Returns boolean indicating whether the visited class is marked with the <code>@Exclude</code>
	 * annotation or not.
	 *
	 * @return boolean <code>true</code> if visited class is marked with <code>@Exclude</code> annotation,
	 * otherwise <code>false</code>
	 */
	public boolean isClassExcluded() {
		return excluded;
	}
}
