package org.deuce.transform;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.deuce.objectweb.asm.ClassReader;
import org.deuce.objectweb.asm.ClassWriter;
import org.deuce.reflection.UnsafeHolder;
import org.deuce.transaction.Context;
import org.deuce.transaction.ContextDelegator;
import org.deuce.transaction.TwilightContext;
import org.deuce.transform.commons.BaseClassTransformer;
import org.deuce.transform.commons.Exclude;
import org.deuce.transform.commons.ClassByteCode;
import org.deuce.transform.commons.ExcludeIncludeStore;
import org.deuce.transform.commons.ExternalFieldsHolder;
import org.deuce.transform.commons.FramesCodeVisitor;

/**
 * A java agent to dynamically instrument transactional supported classes/
 *
 * Currently gearing it towards just doing Twilight-style transformations.
 * TODO: make agent capable of invoking both kinds of transformations, core/basic and twilight.
 *
 * @author Guy Korland
 * @since 1.0
 */
@Exclude
public class Agent implements ClassFileTransformer {
	final private static Logger logger = Logger.getLogger("org.deuce.agent");
	final private static boolean VERBOSE = Boolean.getBoolean("org.deuce.verbose");
	final private static boolean GLOBAL_TXN = Boolean.getBoolean("org.deuce.transaction.global");
	final private static boolean ENABLE_TWILIGHT = Boolean.getBoolean("org.deuce.transaction.enableTwilight");

	/**
	 * @see java.lang.instrument.ClassFileTransformer#transform(java.lang.ClassLoader,
	 *      java.lang.String, java.lang.Class, java.security.ProtectionDomain,
	 *      byte[])
	 */
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			ProtectionDomain protectionDomain, byte[] classfileBuffer)
	throws IllegalClassFormatException {
		try {
			// Don't transform classes from the boot classLoader.
			if (loader != null)
				return transform(className, classfileBuffer, false).get(0).getBytecode();
		}
		catch(Exception e) {
			logger.log( Level.SEVERE, "Fail on class transform: " + className, e);
		}
		return classfileBuffer;
	}

	/**
	 * Transforms a single class' bytecode appropriately (provided the class is not marked as excluded, either explicitly
	 * using the @Exclude annotation or through a JVM argument).
	 *
	 * @param offline <code>true</code> if this is an offline transform.
	 */
	private List<ClassByteCode> transform(String className, byte[] classfileBuffer, boolean offline)
	throws IllegalClassFormatException {
		ArrayList<ClassByteCode> byteCodes = new ArrayList<ClassByteCode>(); // a single .class file can become multiple when in offline mode. Additional .class files are created to hold synthetic fields. I don't *think* this is done during online instrumentation.

		// if the class name starts with a $ or it is marked as excluded, then perform no transformation; put the bytecode into the output untransformed
		if (className.startsWith("$") || ExcludeIncludeStore.exclude(className)){
			byteCodes.add(new ClassByteCode( className, classfileBuffer));
			return byteCodes;
		}

		if (logger.isLoggable(Level.FINER))
			logger.finer("Transforming: Class=" + className);

		// Reads the bytecode and calculate the frames, to support 1.5- code.
		classfileBuffer = addFrames(className, classfileBuffer);

		if(GLOBAL_TXN){
			//ByteCodeVisitor cv = new org.deuce.transaction.global.ClassTransformer( className); // TODO: commented out this for now
			org.deuce.transform.commons.BaseClassTransformer cv = new org.deuce.transaction.global.ClassTransformer( className); // FIXME: using ByteCodeVisitor from core package for now...
			byte[] bytecode = cv.visit(classfileBuffer);

			byteCodes.add(new ClassByteCode( className, bytecode));
		}
		else{
			ExternalFieldsHolder fieldsHolder = null;
			if(offline) {
				fieldsHolder = new ExternalFieldsHolder(className);
			}

			// if org.deuce.transaction.contextChosen property chosen is a Context which does NOT also implement TwilightContext, show error and exit
			Class<? extends Context> contextClass = getContextClass();
			if(ENABLE_TWILIGHT && !implementsInterface(contextClass,TwilightContext.class)) {
				String errMsg = "org.deuce.transaction.enableTwilight option set to true whilst using a context class (via org.deuce.transaction.contextClass property), "+contextClass.getName()+", that does not support Twilight operations ("+contextClass.getName()+" does not implement the TwilightContext interface).";
				logger.severe(errMsg);
				System.err.println(errMsg); // put directly on stderr to ensure error message is seen
				System.exit(1); // error exit
			}

			// start visiting the class (its bytecode); returned is the transformed class bytecode
			ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			TraceClassVisitor tcv; // TODO: use trace visitor to see the bytecode output on the console
			// choose class transformer visitor, either the ordinary one or twilight one. Twilight one only compatible with use of a Context that implements TwilightContext interface.
			BaseClassTransformer cv = ENABLE_TWILIGHT ? new org.deuce.transform.twilight.ClassTransformer(cw, className, fieldsHolder)
													  : new org.deuce.transform.core.ClassTransformer(cw, className, fieldsHolder);
			// parses bytecode and generates visit events when accept() called on it
			ClassReader cr = new ClassReader(classfileBuffer);
			// fire all visiting events corresponding to the bytecode structure; our transformation visitor cv handles the events
			cr.accept(cv, ClassReader.EXPAND_FRAMES);
			//
			byte[] bytecode = cw.toByteArray();

			byteCodes.add(new ClassByteCode( className, bytecode));
			// NOTE: offline instrumentation means that the synthetic fields are put in a separate xxxDeuceFieldsHolder class rather than added to the original class (don't know why yet)
			if(offline) {
				// get bytecode for the additional generated XXXDeuceFieldsHolder class and add it to byteCodes List (remember, separate field holder class only happens in offline mode)
				byteCodes.add(fieldsHolder.getClassByteCode());
			}
		}

		if(VERBOSE) {
			try {
				verbose(byteCodes);
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		return byteCodes;
	}

	private Class<? extends Context> getContextClass() {
		String className = System.getProperty( "org.deuce.transaction.contextClass");
		if( className != null){
			try {
				return (Class<? extends Context>) Class.forName(className);
			} catch (Exception e) {
				e.printStackTrace(); // TODO add logger
			}
		}
		return ContextDelegator.DEFAULT_CONTEXT_CLASS;
	}

	public static boolean implementsInterface(Class<?> clazz, Class<?> interf){
	    for (Class<?> c : clazz.getInterfaces()) {
	        if (c.equals(interf)) {
	            return true;
	        }
	    }
	    return false;
	}

	/**
	 * Reads the bytecode and calculate the frames, to support 1.5- code.
	 *
	 * @param className class to manipulate
	 * @param classfileBuffer original byte code
	 *
	 * @return bytecode with frames
	 */
	private byte[] addFrames(String className, byte[] classfileBuffer) {

		try{
			FramesCodeVisitor frameCompute = new FramesCodeVisitor( className);
			return frameCompute.visit( classfileBuffer); // avoid adding frames to Java6
		}
		catch( FramesCodeVisitor.VersionException ex){
			return classfileBuffer;
		}
	}

	/**
	 * Used for ONLINE instrumentation.
	 *
	 * @param agentArgs
	 * @param inst
	 * @throws Exception
	 */
	public static void premain(String agentArgs, Instrumentation inst) throws Exception{
		UnsafeHolder.getUnsafe();
		logger.fine("Starting Deuce agent");
		inst.addTransformer(new Agent());
	}

	/**
	 * Used for OFFLINE instrumentation.
	 *
	 * TODO: consider whether using agentmain() instead of main() is more appropriate. I don't know; would have to look into it.
	 *
	 * @param args input jar & output jar (multiple input and output jars can be given by separating them with a semicolon)
	 * e.g.: "C:\Java\jdk1.6.0_19\jre\lib\rt.jar" "C:\rt.jar"
	 * @throws Exception
	 */
	public static void main(String[] args) throws Exception{
//		logger.setLevel(Level.ALL); // show detailed logging

		UnsafeHolder.getUnsafe();
		logger.fine("Starting Deuce translator");

		// TODO check args
		Agent agent = new Agent();
		agent.transformJar(args[0], args[1]);
	}

	private void transformJar( String inFileNames, String outFilenames) throws IOException, IllegalClassFormatException {
		String[] inFileNamesArr = inFileNames.split(";");
		String[] outFilenamesArr = outFilenames.split(";");
		if(inFileNamesArr.length != outFilenamesArr.length)
			throw new IllegalArgumentException("Input files list length doesn't match output files list.");

		for(int i=0 ; i<inFileNamesArr.length ; ++i){
			String inFileName = inFileNamesArr[i];
			String outFilename = outFilenamesArr[i];

			final int size = 4096;
			byte[] buffer = new byte[size];
			ByteArrayOutputStream baos = new ByteArrayOutputStream(size);
			JarInputStream jarIS = new JarInputStream(new FileInputStream(inFileName));
			JarOutputStream jarOS = new JarOutputStream(new FileOutputStream(outFilename), jarIS.getManifest());

			logger.info("Start translating source:" + inFileName + " target:" + outFilename);

			String nextName = "";
			try {
				for (JarEntry nextJarEntry = jarIS.getNextJarEntry(); nextJarEntry != null;
				nextJarEntry = jarIS.getNextJarEntry()) {

					baos.reset();
					int read;
					while ((read = jarIS.read(buffer, 0, size)) > 0) {
						baos.write(buffer, 0, read);
					}
					byte[] bytecode = baos.toByteArray();

					nextName = nextJarEntry.getName();
					if( nextName.endsWith(".class")){
						if( logger.isLoggable(Level.FINE)){
							logger.fine("Translating " + nextName);
						}
						String className = nextName.substring(0, nextName.length() - ".class".length());
						List<ClassByteCode> transformBytecodes = transform( className, bytecode, true);
						for(ClassByteCode byteCode : transformBytecodes){
							JarEntry transformedEntry = new JarEntry(byteCode.getClassName() + ".class");
							jarOS.putNextEntry( transformedEntry);
							jarOS.write( byteCode.getBytecode());
						}
					}
					else{
						jarOS.putNextEntry( nextJarEntry);
						jarOS.write(bytecode);
					}
				}

			}
			catch(Exception e){
				logger.log(Level.SEVERE, "Failed to translate " + nextName, e);
			}
			finally {
				logger.info("Closing source:" + inFileName + " target:" + outFilename);
				jarIS.close();
				jarOS.close();
			}
		}
	}

	private void verbose(List<ClassByteCode> byteCodes) throws FileNotFoundException,
	IOException {
		File verbose = new File( "verbose");
		verbose.mkdir();

		for( ClassByteCode byteCode : byteCodes){
			String[] packages = byteCode.getClassName().split("/");
			File file = verbose;
			for( int i=0 ; i<packages.length-1 ; ++i){
				file = new File( file, packages[i]);
				file.mkdir();
			}
			file = new File( file, packages[packages.length -1]);
			FileOutputStream fs = new FileOutputStream( file + ".class");
			fs.write(byteCode.getBytecode());
			fs.close();
		}
	}
}
