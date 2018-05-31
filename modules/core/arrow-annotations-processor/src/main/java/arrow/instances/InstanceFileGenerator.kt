package arrow.instances

import arrow.common.Package
import arrow.common.utils.*
import me.eugeniomarletti.kotlin.metadata.modality
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.ProtoBuf
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.deserialization.TypeTable
import java.io.File

data class FunctionMapping(
  val name: String,
  val typeclass: ClassOrPackageDataWrapper.Class,
  val function: ProtoBuf.Function,
  val retTypeName: String)

data class Instance(
  val `package`: Package,
  val target: AnnotatedInstance
) {
  val name = target.classElement.simpleName

  val receiverTypeName = target.dataType.nameResolver.getString(target.dataType.classProto.fqName).replace("/", ".")

  val receiverTypeSimpleName = receiverTypeName.substringAfterLast(".")

  val companionFactoryName = name.toString()
    .replace(receiverTypeSimpleName, "")
    .replace("Instance", "")
    .decapitalize()

  fun expandedTypeArgs(reified: Boolean = false): String =
    if (target.classOrPackageProto.typeParameters.isNotEmpty()) {
      target.classOrPackageProto.typeParameters.joinToString(
        prefix = "<",
        separator = ", ",
        transform = {
          val name = target.classOrPackageProto.nameResolver.getString(it.name)
          if (reified) "reified $name" else name
        },
        postfix = ">"
      )
    } else {
      ""
    }

  fun typeConstraints() = target.classOrPackageProto.typeConstraints()

  private val abstractFunctions: List<FunctionMapping> =
    getTypeclassReturningFunctions().fold(emptyList(), normalizeOverridenFunctions())

  private fun normalizeOverridenFunctions(): (List<FunctionMapping>, FunctionMapping) -> List<FunctionMapping> =
    { acc, func ->
      val retType = func.function.returnType.extractFullName(func.typeclass).removeBackticks()
      val existingParamInfo = getParamInfo(func.name to retType)
      when {
        acc.contains(func) -> acc //if the same param was already added ignore it
        else -> { //remove accumulated functions whose return types  supertypes of the current evaluated one and add the current one
          val remove = acc.find { av ->
            val avRetType = av.function.returnType.extractFullName(av.typeclass)
              .removeBackticks().replace(".", "/").substringBefore("<")
            existingParamInfo.superTypes.contains(avRetType)
          }
          val ignore = acc.any { av ->
            val avRetTypeUnparsed = av.function.returnType.extractFullName(av.typeclass)
              .removeBackticks()
            val parsedRetType = retType.replace(".", "/").substringBefore("<")
            val avParamInfo = getParamInfo(av.name to avRetTypeUnparsed)
            avParamInfo.superTypes.contains(parsedRetType)
          }
          when {
            remove != null -> (acc - remove) + listOf(func)
            ignore -> acc
            else -> acc + listOf(func)
          }
        }
      }
    }

  private fun getTypeclassReturningFunctions(): List<FunctionMapping> =
    (target.superTypes + listOf(target.classOrPackageProto)).flatMap { tc ->
      tc.functionList
        .filter { it.modality == ProtoBuf.Modality.ABSTRACT }
        .map { it to it.returnType.extractFullName(tc).removeBackticks() }
        // FIXME(paco): number of parameters and naming convention, used to be based off the TC interface
        .filter { (it, name) -> it.valueParameterCount == 0 && name.contains("typeclass") }
        .distinctBy { (_, name) -> name }
        .flatMap { (it, name) ->
          val retTypeName = name.substringBefore("<")
          val retType = target.processor.elementUtils.getTypeElement(retTypeName)
          when {
            retType != null -> {
              listOf(FunctionMapping(tc.nameResolver.getString(it.name), tc, it, retTypeName))
            }
            else -> emptyList()
          }
        }
    }

  data class ParamInfo(
    val param: Pair<String, String>,
    val superTypes: List<String>,
    val paramTypeName: String,
    val typeVarName: String)

  private fun getParamInfo(param: Pair<String, String>): ParamInfo {
    val typeVarName = param.second.substringAfter("<").substringBefore(">")
    val rt = target.processor.elementUtils.getTypeElement(param.second.substringBefore("<"))
    val paramType = target.processor.getClassOrPackageDataWrapper(rt) as ClassOrPackageDataWrapper.Class
    val paramTypeName = paramType.nameResolver.getString(paramType.classProto.fqName)
    val typeTable = TypeTable(paramType.classProto.typeTable)
    val superTypes = target.processor.recurseTypeclassInterfaces(paramType, typeTable, emptyList()).map {
      val t = it as ClassOrPackageDataWrapper.Class
      t.nameResolver.getString(t.classProto.fqName)
    }
    return ParamInfo(param, superTypes, paramTypeName, typeVarName)
  }

  val args: List<Pair<String, String>> = abstractFunctions.sortedBy { f ->
    val typeClassTypeArgs = f.typeclass.typeParameters.map { f.typeclass.nameResolver.getString(it.name) }
    val functionRetTypeTypeArgs = f.function.returnType.extractFullName(f.typeclass)
      .removeBackticks().substringAfter("<").substringBefore(">")
    typeClassTypeArgs.indexOf(functionRetTypeTypeArgs)
  }.map { (name, tc, func) ->
    val retType = func.returnType.extractFullName(tc)
    name to retType.removeBackticks()
  }

  val targetImplementations = args.joinToString(
    separator = "\n",
    transform = {
      "      override fun ${it.first}(): ${it.second} = ${it.first}"
    }
  )

}

class InstanceFileGenerator(
  private val generatedDir: File,
  private val annotatedList: List<AnnotatedInstance>
) {

  private val instances: List<Instance> = annotatedList.map { Instance(it.dataType.`package`, it) }

  /**
   * Main entry point for deriving extension generation
   */
  fun generate() {
    instances.forEach {
      val elementsToGenerate: List<String> =
        listOf(genImports(it), genCompanionExtensions(it))
      val source: String = elementsToGenerate.joinToString(prefix = "package ${it.`package`}\n\n", separator = "\n", postfix = "\n")
      val file = File(generatedDir, instanceAnnotationClass.simpleName + ".${it.target.classElement.qualifiedName}.kt")
      file.writeText(source)
    }
  }

  private fun genImports(i: Instance): String = """
            |import ${i.target.classOrPackageProto.`package`}.*
            |""".trimMargin()

  private fun genCompanionExtensions(i: Instance): String =
    """|
                |fun ${i.expandedTypeArgs(reified = false)} ${i.receiverTypeName}.Companion.${i.companionFactoryName}(${(i.args.map {
      "${it.first}: ${it.second}"
    } + (if (i.args.isNotEmpty()) listOf("@Suppress(\"UNUSED_PARAMETER\") dummy: kotlin.Unit = kotlin.Unit") else emptyList())).joinToString(", ")
    }): ${i.name}${i.expandedTypeArgs()}${i.typeConstraints()} =
                |    object : ${i.name}${i.expandedTypeArgs()} {
                |${i.targetImplementations}
                |    }
                |
                |""".trimMargin()
}
