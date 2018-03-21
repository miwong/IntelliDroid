Extracts GUI method runtime annotations to help with GUI method analysis.

Usage: ./guiAnnotationExtract.sh <apk> <output.json>

Manually copy the output json into the preprocessed APK folder as guiAnnotation.json for now: this needs Java 8 due to dexlib2
but the rest of IntelliDroid doesn't like Java 8. Will rebuild dexlib2 with Java 7 later.

Implemented using [dexlib2](https://github.com/JesusFreke/smali/tree/master/dexlib2).

Currently supports [ButterKnife](https://github.com/JakeWharton/butterknife)'s OnClick attributes.

This is required since Dare doesn't preserve annotations. Other converters such as Dex2jar do preserve annotations, but cannot handle malformed Dex files that Dare can easily handle.

Note: only run this on non-malicious apps from trusted sources! While there shouldn't be any security issues in this code, it has not been audited for them.
