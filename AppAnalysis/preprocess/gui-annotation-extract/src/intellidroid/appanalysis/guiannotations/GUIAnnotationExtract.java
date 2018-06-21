package intellidroid.appanalysis.guiannotations;

import java.io.*;
import java.util.*;
import org.json.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.dexbacked.*;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.value.*;

/**
 * A utility to extract GUI-related annotations from an APK to a JSON file.
 * Currently supports extracting ButterKnife.OnClick annotations from methods.
 */
public class GUIAnnotationExtract {

	public static String getBytecodeSignature(DexBackedMethod dexMethod) {
		StringBuilder builder = new StringBuilder();
		builder.append(dexMethod.getName()).append("(");
		for (String s: dexMethod.getParameterTypes()) {
			builder.append(s);
		}
		builder.append(")").append(dexMethod.getReturnType());
		return builder.toString();
	}

	public static void processDexFile(DexBackedDexFile dexFile, JSONArray outArray) throws Exception {
		for (DexBackedClassDef dexClass : dexFile.getClasses()) {
			for (DexBackedMethod dexMethod: dexClass.getMethods()) {
				for (Annotation annotation: dexMethod.getAnnotations()) {
					String annotationType = annotation.getType();
					// we only handle onClick for now.
					boolean found = annotationType.equals("Lbutterknife/OnClick;");
					if (found) {
						List<? extends EncodedValue> viewIds = null;
						for (AnnotationElement element: annotation.getElements()) {
							if (element.getName().equals("value")) {
								viewIds = ((ArrayEncodedValue)element.getValue()).getValue();
								break;
							}
						}
						JSONArray viewIdsArray = new JSONArray();
						if (viewIds == null) {
							System.out.println("ViewHolder-based binding not handled: " +
								dexClass.getType() + "->" + dexMethod.getName());
						} else {
							for (EncodedValue value: viewIds) {
								viewIdsArray.put(((IntEncodedValue)value).getValue());
							}
						}
						JSONObject outObj = new JSONObject();
						String className = dexClass.getType();
						outObj.put("className", className.substring(0, className.length() - 1));
						outObj.put("method", getBytecodeSignature(dexMethod));
						outObj.put("annotation", annotationType);
						outObj.put("viewIds", viewIdsArray);
						outArray.put(outObj);
					}
				}
			}
		}
	}

	public static void doExtract(File[] inputFile, File outputFile) throws Exception {
		JSONArray outArray = new JSONArray();
		for (File inFile : inputFile) {
			MultiDexContainer<? extends DexBackedDexFile> dexFiles = DexFileFactory.loadDexContainer(inFile, Opcodes.getDefault());
			for (String dexName: dexFiles.getDexEntryNames()) {
				DexBackedDexFile dexFile = dexFiles.getEntry(dexName);
				processDexFile(dexFile, outArray);
			}
		}
		PrintStream outStream = new PrintStream(outputFile);
		outStream.println(outArray.toString(4));
	}

	public static void main(String[] args) throws Exception {
		File[] inFiles = new File[args.length - 1];
		for (int i = 0; i < args.length - 1; i++) {
			inFiles[i] = new File(args[i]);
		}
		File outFile = new File(args[args.length - 1]);
		doExtract(inFiles, outFile);
	}
}
