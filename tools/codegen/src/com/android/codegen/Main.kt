package com.android.codegen

import com.github.javaparser.JavaParser
import java.io.File


const val THIS_SCRIPT_LOCATION = ""
const val GENERATED_WARNING_PREFIX = "Code below generated by $CODEGEN_NAME"
const val INDENT_SINGLE = "    "

val PRIMITIVE_TYPES = listOf("byte", "short", "int", "long", "char", "float", "double", "boolean")

val BUILTIN_SPECIAL_PARCELLINGS = listOf("Pattern")

const val FLAG_BUILDER_PROTECTED_SETTERS = "--builder-protected-setters"
const val FLAG_NO_FULL_QUALIFIERS = "--no-full-qualifiers"

val JAVA_PARSER = JavaParser()

/** @see [FeatureFlag] */
val USAGE = """
Usage: $CODEGEN_NAME [--[PREFIX-]FEATURE...] JAVAFILE

Generates boilerplade parcelable/data class code at the bottom of JAVAFILE, based o fields' declaration in the given JAVAFILE's top-level class

FEATURE represents some generatable code, and can be among:
${FeatureFlag.values().map { feature ->
    "  ${feature.kebabCase}" to feature.desc
}.columnize(" - ")}

And PREFIX can be:
  <empty> - request to generate the feature
    no    - suppress generation of the feature
  hidden  - request to generate the feature with @hide

Extra options:
  --help        - view this help
  --update-only - auto-detect flags from the previously auto-generated comment within the file
  $FLAG_NO_FULL_QUALIFIERS
                - when referring to classes don't use package name prefix; handy with IDE auto-import
  $FLAG_BUILDER_PROTECTED_SETTERS
                - make builder's setters protected to expose them as public in a subclass on a whitelist basis


Special field modifiers and annotations:
  transient                 - ignore the field completely
  @Nullable                 - support null value when parcelling, and never throw on null input
  @NonNull                  - throw on null input and don't parcel the nullness bit for the field
  @DataClass.Enum           - parcel field as an enum value by ordinal
  @DataClass.PluralOf(..)   - provide a singular version of a collection field name to be used in the builder's 'addFoo(..)'
  @DataClass.ParcelWith(..) - provide a custom Parcelling class, specifying the custom (un)parcelling logic for this field
  = <initializer>;          - provide default value and never throw if this field was not provided e.g. when using builder
  /** ... */                - copy given javadoc on field's getters/setters/constructor params/builder setters etc.
  @hide (in javadoc)        - force field's getters/setters/withers/builder setters to be @hide-den if generated


Special methods/etc. you can define:

  <any auto-generatable method>
      For any method to be generated, if a method with same name and argument types is already
      defined, than that method will not be generated.
      This allows you to override certain details on granular basis.

  void onConstructed()
      Will be called in constructor, after all the fields have been initialized.
      This is a good place to put any custom validation logic that you may have

  static class $CANONICAL_BUILDER_CLASS extends $BASE_BUILDER_CLASS
      If a class extending $BASE_BUILDER_CLASS is specified, generated builder's setters will
      return the provided $CANONICAL_BUILDER_CLASS type.
      $BASE_BUILDER_CLASS's constructor(s) will be package-private to encourage using $CANONICAL_BUILDER_CLASS instead
      This allows you to extend the generated builder, adding or overriding any methods you may want


In addition, for any field mMyField(or myField) of type FieldType you can define the following methods:

  void parcelMyField(Parcel dest, int flags)
      Allows you to provide custom logic for storing mMyField into a Parcel

  static FieldType unparcelMyField(Parcel in)
      Allows you to provide custom logic to deserialize the value of mMyField from a Parcel

  String myFieldToString()
      Allows you to provide a custom toString representation of mMyField's value

  FieldType lazyInitMyField()
      Requests a lazy initialization in getMyField(), with the provided method being the constructor
      You may additionally mark the fields as volatile to cause this to generate a thread-safe
      double-check locking lazy initialization

  FieldType defaultMyField()
      Allows you to provide a default value to initialize the field to, in case an explicit one
      was not provided.
      This is an alternative to providing a field initializer that, unlike the initializer,
      you can use with final fields.

Version: $CODEGEN_VERSION
Questions? Feedback? Contact: eugenesusla@
"""

fun main(args: Array<String>) {
    if (args.contains("--help")) {
        println(USAGE)
        System.exit(0)
    }
    if (args.contains("--version")) {
        println(CODEGEN_VERSION)
        System.exit(0)
    }
    val file = File(args.last())
    val sourceLinesNoClosingBrace = file.readLines().dropLastWhile {
        it.startsWith("}") || it.all(Char::isWhitespace)
    }
    val cliArgs = handleUpdateFlag(args, sourceLinesNoClosingBrace)
    val sourceLinesAsIs = discardGeneratedCode(sourceLinesNoClosingBrace)
    val sourceLines = sourceLinesAsIs
            .filterNot { it.trim().startsWith("//") }
            .map { it.trimEnd().dropWhile { it == '\n' } }

    val stringBuilder = StringBuilder(sourceLinesAsIs.joinToString("\n"))
    ClassPrinter(sourceLines, stringBuilder, cliArgs).run {

        val cliExecutable = "$THIS_SCRIPT_LOCATION$CODEGEN_NAME"
        val fileEscaped = file.absolutePath.replace(
                System.getenv("ANDROID_BUILD_TOP"), "\$ANDROID_BUILD_TOP")


        +"""



        // $GENERATED_WARNING_PREFIX v$CODEGEN_VERSION.
        //
        // DO NOT MODIFY!
        //
        // To regenerate run:
        // $ $cliExecutable ${cliArgs.dropLast(1).joinToString("") { "$it " }}$fileEscaped
        //
        // CHECKSTYLE:OFF Generated code
        """

        if (FeatureFlag.CONST_DEFS()) generateConstDefs()


        if (FeatureFlag.CONSTRUCTOR()) {
            generateConstructor("public")
        } else if (FeatureFlag.BUILDER()
                || FeatureFlag.COPY_CONSTRUCTOR()
                || FeatureFlag.WITHERS()
                || FeatureFlag.PARCELABLE()) {
            generateConstructor("/* package-private */")
        }
        if (FeatureFlag.COPY_CONSTRUCTOR()) generateCopyConstructor()

        if (FeatureFlag.GETTERS()) generateGetters()
        if (FeatureFlag.SETTERS()) generateSetters()
        if (FeatureFlag.TO_STRING()) generateToString()
        if (FeatureFlag.EQUALS_HASH_CODE()) generateEqualsHashcode()

        if (FeatureFlag.FOR_EACH_FIELD()) generateForEachField()

        if (FeatureFlag.WITHERS()) generateWithers()

        if (FeatureFlag.PARCELABLE()) generateParcelable()

        if (FeatureFlag.BUILDER() && FeatureFlag.BUILD_UPON()) generateBuildUpon()
        if (FeatureFlag.BUILDER()) generateBuilder()

        if (FeatureFlag.AIDL()) generateAidl(file)

        generateMetadata(file)

        rmEmptyLine()
    }
    stringBuilder.append("\n}\n")
    file.writeText(stringBuilder.toString().mapLines { trimEnd() })
}

internal fun discardGeneratedCode(sourceLinesNoClosingBrace: List<String>): List<String> {
    return sourceLinesNoClosingBrace
            .takeWhile { GENERATED_WARNING_PREFIX !in it }
            .dropLastWhile(String::isBlank)
}

private fun handleUpdateFlag(cliArgs: Array<String>, sourceLines: List<String>): Array<String> {
    if ("--update-only" in cliArgs
            && sourceLines.none { GENERATED_WARNING_PREFIX in it || it.startsWith("@DataClass") }) {
        System.exit(0)
    }
    return cliArgs - "--update-only"
}