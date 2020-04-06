package protein.kotlinbuilders

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import com.google.gson.annotations.SerializedName
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName
import io.reactivex.Completable
import io.reactivex.Single
import io.swagger.models.HttpMethod
import io.swagger.models.ModelImpl
import io.swagger.models.Operation
import io.swagger.models.RefModel
import io.swagger.models.parameters.BodyParameter
import io.swagger.models.parameters.Parameter
import io.swagger.models.properties.ArrayProperty
import io.swagger.models.properties.DoubleProperty
import io.swagger.models.properties.FloatProperty
import io.swagger.models.properties.LongProperty
import io.swagger.models.properties.RefProperty
import io.swagger.models.properties.StringProperty
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.DateSchema
import io.swagger.v3.oas.models.media.DateTimeSchema
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.util.RefUtils
import protein.common.StorageUtils
import protein.extensions.snake
import protein.tracking.ErrorTracking
import java.io.FileNotFoundException
import java.lang.IllegalStateException
import java.lang.StringBuilder
import java.net.UnknownHostException
import java.util.*
import kotlin.collections.ArrayList
import kotlin.reflect.KClass

class KotlinApiBuilder(
    private val proteinApiConfiguration: ProteinApiConfiguration,
    private val errorTracking: ErrorTracking
) {
    companion object {
        const val OK_RESPONSE = "200"
        const val ARRAY_SWAGGER_TYPE = "array"
        const val INTEGER_SWAGGER_TYPE = "integer"
        const val NUMBER_SWAGGER_TYPE = "number"
        const val STRING_SWAGGER_TYPE = "string"
        const val BOOLEAN_SWAGGER_TYPE = "boolean"
        const val REF_SWAGGER_TYPE = "ref"
    }

    private val swaggerModel: OpenAPI = try {
        if (!proteinApiConfiguration.swaggerUrl.isEmpty()) {
            OpenAPIV3Parser().read(proteinApiConfiguration.swaggerUrl)
        } else {
            OpenAPIV3Parser().read(proteinApiConfiguration.swaggerFile)
        }
    } catch (unknown: UnknownHostException) {
        errorTracking.logException(unknown)
        OpenAPI()
    } catch (illegal: IllegalStateException) {
        errorTracking.logException(illegal)
        OpenAPI()
    } catch (notFound: FileNotFoundException) {
        errorTracking.logException(notFound)
        OpenAPI()
    }

    private lateinit var apiInterfaceTypeSpec: TypeSpec
    private val models: MutableMap<String, Schema<Any>> = mutableMapOf()
    private val linkModels: MutableMap<String, Schema<Any>> = mutableMapOf()
    private val modelsWithLinks: MutableMap<String, Schema<Any>> = mutableMapOf()
    private var mods: List<ModelEntity> = listOf()
    private var modLinks: List<ModelEntity> = listOf()
    private val syncEntityModelListTypeSpec: ArrayList<TypeSpec> = ArrayList()
    private val databaseEntitiesTypeSpec: ArrayList<TypeSpec> = ArrayList()
    private val baseDaoTypeSpec: ArrayList<TypeSpec> = ArrayList()
    private val daoTypeSpec: ArrayList<TypeSpec> = ArrayList()
    private lateinit var databaseTypeSpec: TypeSpec
    private lateinit var mapHelperTypeSpec: TypeSpec
    private lateinit var mapPutHelperTypeSpec: TypeSpec
    private val domainEntitiesSpec: ArrayList<TypeSpec> = ArrayList()
    private val domainMappersSpec: ArrayList<TypeSpec> = ArrayList()
    private val mappersTypeSpec: ArrayList<TypeSpec> = ArrayList()
    private val enumListTypeSpec: ArrayList<TypeSpec> = ArrayList()
    private lateinit var triggersHelperTypeSpec: TypeSpec

    fun build() {
        filterModels()
        mods = convert(models)
        modLinks = convert(modelsWithLinks)
        /*val links: MutableList<ModelEntity> = mutableListOf()
        mods.forEach { model ->
            if (model is ObjectEntity) {
                model.props.forEach {
                    if (it is ArrayProp && it.type is ObjectProp) {
                        val link = ObjectSchema()
                        val prop = IntegerSchema()
                        prop.nullable = false
                        val withoutGroup = it.type.objectName
                        val key = model.name
                        link.properties = mutableMapOf()
                        link.properties["id$withoutGroup"] = prop
                        link.properties["id$key"] = prop
                        linkModels[key + withoutGroup + "Link"] = link
                        links.add(model)
                    }
                }
            }
        }*/
        val p = proteinApiConfiguration.packageName

        createSyncEntityModels(p)
        createDatabaseEntities(p)
        createDao(p)
        createDatabase(p)
        createMappers(p)
        createEnums("de.juvopro.bms")
        createMapHelper(p)
        createPutMapHelper(p)
        createUndoTriggers(p)
    }

    fun getGeneratedTypeSpec(): TypeSpec {
        return apiInterfaceTypeSpec
    }

    fun generateFiles(
        isSyncDto: Boolean,
        isDatabaseEntities: Boolean,
        isBaseDao: Boolean,
        isDao: Boolean,
        isDatabase: Boolean,
        isMappers: Boolean,
        isHelper: Boolean,
        isPutHelper: Boolean,
        isEnums: Boolean,
        isTriggers: Boolean
    ) {
        /*StorageUtils.generateFiles(
          proteinApiConfiguration.moduleName, proteinApiConfiguration.packageName, apiInterfaceTypeSpec)*/
        if (isSyncDto) {
            for (typeSpec in syncEntityModelListTypeSpec) {
                StorageUtils.generateFiles(
                    proteinApiConfiguration.moduleName, proteinApiConfiguration.packageName + ".sync.entity", typeSpec)
            }
        }
        if (isDatabaseEntities) {
            for (typeSpec in databaseEntitiesTypeSpec) {
                StorageUtils.generateFiles(
                    proteinApiConfiguration.moduleName, proteinApiConfiguration.packageName + ".database.entity", typeSpec)
            }
        }
        if (isBaseDao) {
            for (typeSpec in baseDaoTypeSpec) {
                StorageUtils.generateFiles(
                    proteinApiConfiguration.moduleName, proteinApiConfiguration.packageName + ".database.dao.base", typeSpec)
            }
        }
        if (isDao) {
            for (typeSpec in daoTypeSpec) {
                StorageUtils.generateFiles(
                    proteinApiConfiguration.moduleName, proteinApiConfiguration.packageName + ".database.dao.temp", typeSpec)
            }
        }
        if (isDatabase) {
            StorageUtils.generateFiles(
                proteinApiConfiguration.moduleName, proteinApiConfiguration.packageName + ".database", databaseTypeSpec)
        }
        if (isMappers) {
            for (typeSpec in mappersTypeSpec) {
                StorageUtils.generateFiles(
                    proteinApiConfiguration.moduleName, proteinApiConfiguration.packageName + ".sync.mapper", typeSpec)
            }
        }
        if (isHelper) {
            StorageUtils.generateFiles(
                proteinApiConfiguration.moduleName, proteinApiConfiguration.packageName + ".sync.mapper.helper", mapHelperTypeSpec)
        }
        if (isPutHelper) {
            StorageUtils.generateFiles(
                proteinApiConfiguration.moduleName, proteinApiConfiguration.packageName + ".sync.mapper.helper", mapPutHelperTypeSpec)
        }
        if (isEnums) {
            for (typeSpec in enumListTypeSpec) {
                StorageUtils.generateFiles(
                    proteinApiConfiguration.moduleName, "de.juvopro.bms.base.model", typeSpec)
            }
        }
        if (isTriggers) {
            StorageUtils.generateFiles(
                proteinApiConfiguration.moduleName, proteinApiConfiguration.packageName + ".database.trigger", triggersHelperTypeSpec)
        }
        /*
        if (isDomainEntities) {
            for (typeSpec in domainEntitiesSpec) {
                StorageUtils.generateFiles(
                    proteinApiConfiguration.moduleName, proteinApiConfiguration.packageName + ".domain.model", typeSpec)
            }
        }
        if (isDomainMappers) {
            for (typeSpec in domainMappersSpec) {
                StorageUtils.generateFiles(
                    proteinApiConfiguration.moduleName, proteinApiConfiguration.packageName + ".domain.mapper", typeSpec)
            }
        }*/
        /*for (typeSpec in enumListTypeSpec) {
          StorageUtils.generateFiles(
            proteinApiConfiguration.moduleName, proteinApiConfiguration.packageName, typeSpec)
        }*/
    }

    private fun createEnumClasses() {
        addOperationResponseEnums()
        addModelEnums()
    }

    private fun addModelEnums() {
        /*if (swaggerModel.definitions != null && !swaggerModel.definitions.isEmpty()) {
            for (definition in swaggerModel.definitions) {
                if (definition.value != null && definition.value.properties != null) {
                    for (modelProperty in definition.value.properties) {
                        if (modelProperty.value is StringProperty) {
                            val enumDefinition = (modelProperty.value as StringProperty).enum
                            if (enumDefinition != null) {
                                val enumTypeSpecBuilder = TypeSpec.enumBuilder(modelProperty.key.capitalize())
                                for (constant in enumDefinition) {
                                    enumTypeSpecBuilder.addEnumConstant(constant)
                                }
                                if (!enumListTypeSpec.contains(enumTypeSpecBuilder.build())) {
                                    enumListTypeSpec.add(enumTypeSpecBuilder.build())
                                }
                            }
                        }
                    }
                }
            }
        }*/
    }

    private fun addOperationResponseEnums() {
        /*if (swaggerModel.paths != null && !swaggerModel.paths.isEmpty()) {
            for (path in swaggerModel.paths) {
                for (operation in path.value.operationMap) {
                    try {
                        for (parameters in operation.value.parameters) {
                            if (parameters is PathParameter) {
                                if (parameters.enum != null) {
                                    val enumTypeSpecBuilder = TypeSpec.enumBuilder(parameters.name.capitalize())
                                    for (constant in parameters.enum) {
                                        enumTypeSpecBuilder.addEnumConstant(constant)
                                    }
                                    if (!enumListTypeSpec.contains(enumTypeSpecBuilder.build())) {
                                        enumListTypeSpec.add(enumTypeSpecBuilder.build())
                                    }
                                }
                            }
                        }
                    } catch (error: Exception) {
                        errorTracking.logException(error)
                    }
                }
            }
        }*/
    }

    private val syncDtoPostfix = "Dto"

    private fun createSyncEntityModels(packageName: String) {
        for (definition in mods) {
            val modelClassTypeSpec: TypeSpec.Builder = TypeSpec.classBuilder(definition.name + syncDtoPostfix)
                .addModifiers(KModifier.DATA)
            if (definition is ObjectEntity) {
                val primaryConstructor = FunSpec.constructorBuilder()
                for (property in definition.props) {
                    val (type, default) = getType(property).let {
                        when (property) {
                            is ObjectProp -> ClassName("$packageName.sync.entity", property.objectName + syncDtoPostfix).asNullable() to CodeBlock.of("null")
                            is ArrayProp -> (when {
                                property.type is ObjectProp -> List::class.asTypeName()
                                    .parameterizedBy(ClassName("$packageName.sync.entity", property.type.objectName + syncDtoPostfix))
                                    .asNonNull()
                                property.type is EnumProp -> List::class.parameterizedBy(String::class).asNonNull()
                                else -> it.asNonNull()
                            }) to CodeBlock.of("%L()", "listOf")
                            else -> (if (property is EnumProp)
                                String::class.asTypeName().asNullable()
                            else it.asNullable()) to CodeBlock.of("null")
                        }
                    }
                    val propertySpec = PropertySpec.builder(property.name, type)
                        .addAnnotation(AnnotationSpec.builder(SerializedName::class)
                            .addMember("\"${property.name}\"")
                            .build())
                        .initializer(property.name)
                        .mutable()
                    val parameter = ParameterSpec.builder(property.name, type)
                        .defaultValue(default)
                    primaryConstructor.addParameter(parameter.build())
                    modelClassTypeSpec.addProperty(propertySpec.build())
                }
                modelClassTypeSpec.primaryConstructor(primaryConstructor.build())

                syncEntityModelListTypeSpec.add(modelClassTypeSpec.build())
            }
        }
        syncEntityModelListTypeSpec
    }

    private fun createDatabaseEntities(packageName: String): List<String> {
        val classNameList = ArrayList<String>()
        val idEntityInterface = ClassName("$packageName.database.entity", "IdEntity")
        val baseEntityInterface = ClassName("$packageName.database.entity", "BaseEntity")
        val deletableEntityInterface = ClassName("$packageName.database.entity", "DeletableEntity")
        val sharedInterface = ClassName("$packageName.database.entity", "SharedData")
        val creatableEntityInterface = ClassName("$packageName.database.entity", "Creatable")
        val associableEntityInterface = ClassName("$packageName.database.entity", "Associable")
        val favoritableEntityInterface = ClassName("$packageName.database.entity", "Favoritable")

        for (definition in modLinks) {
            val modelName = definition.name

            if (skipSyncDataModels(modelName)) continue // skip

            val modelClassTypeSpec: TypeSpec.Builder = TypeSpec.classBuilder(modelName)
                .addModifiers(KModifier.DATA)
            classNameList.add(modelName)

            val foreignKeys = mutableSetOf<String>()
            var base = baseEntityInterface

            if (definition is ObjectEntity) {
                if (when (modelName) {
                        "Phone", "Fax", "Email", "Www" -> true
                        else -> false
                    }) {
                    continue
                }
                val primaryConstructor = FunSpec.constructorBuilder()
                val mutableProps = definition.props.toMutableList()
                if (!modelName.contains("Link") && !mutableProps.map { it.name }.contains("id")) {
                    mutableProps.add(IntegerProp("id"))
                }
                if (modelName == "PersonGroup") {
                    mutableProps.add(StringProp("type"))
                }
                if (modelName.isFavoritableEntity()) {
                    mutableProps.add(BoolProp("isFavorite"))
                }
                for (property in mutableProps) {
                    if (property is ArrayProp && property.type !is StringProp
                        && property.type !is DateProp
                        && property.type !is EnumProp) {
                        continue
                    }
                    var propertyName = property.name
                    var databasePropertyName = propertyName
                    val nullValue = CodeBlock.of("null")
                    val (typeName, default) = when (property) {
                        is ObjectProp -> {
                            propertyName = "id${propertyName.capitalize()}"
                            databasePropertyName = propertyName
                            Int::class.asTypeName().requiredOrNullable(property.nullable) to nullValue
                        }
                        is EnumProp -> ClassName("de.juvopro.bms.base.model", property.enumName).asNullable() to nullValue
                        is ArrayProp -> {
                            if (property.type is EnumProp) {
                                Set::class.asTypeName().parameterizedBy(ClassName("de.juvopro.bms.base.model", property.type.enumName)).asNonNull() to CodeBlock.of("%L()", "setOf")
                            } else getType(property).asNonNull() to CodeBlock.of("%L()", "listOf")
                        }
                        else -> {
                            if (linkModels.containsKey(modelName)) {
                                getType(property).asNonNull() to CodeBlock.of("%L", 0)
                            } else getType(property).asNullable() to nullValue
                        }
                    }
                    val propertySpecBuilder = if (property.name == "id") {
                        databasePropertyName = propertyName + modelName
                        PropertySpec.builder(propertyName, typeName)
                            .addModifiers(KModifier.OVERRIDE)
                            .addAnnotation(AnnotationSpec.builder(PrimaryKey::class)
                                .addMember("autoGenerate = true")
                                .build())
                    } else {
                        if (propertyName.startsWith("id") && propertyName[2].isUpperCase()) {
                            foreignKeys.add(propertyName)
                        }
                        PropertySpec.builder(propertyName, typeName)
                    }
                    if (propertyName == "created" ||
                        propertyName == "modified" ||
                        propertyName == "isDeleted") {
                        if (propertyName == "isDeleted") {
                            base = deletableEntityInterface
                        }
                        propertySpecBuilder.addModifiers(KModifier.OVERRIDE)
                    }

                    if (propertyName == "idPersonGroup" && modelName != "PersonGroupLink") {
                        propertySpecBuilder.addModifiers(KModifier.OVERRIDE)
                        modelClassTypeSpec.addSuperinterface(associableEntityInterface)
                    }

                    if (propertyName == "idPersonCreator") {
                        propertySpecBuilder.addModifiers(KModifier.OVERRIDE)
                        modelClassTypeSpec.addSuperinterface(creatableEntityInterface)
                    }

                    val propertySpec = propertySpecBuilder
                        .initializer(propertyName)
                        .addAnnotation(AnnotationSpec.builder(ColumnInfo::class)
                            .addMember("%L = %S", "name", databasePropertyName.snake())
                            .build())
                        .mutable()
                        .build()
                    val parameter = ParameterSpec.builder(propertyName, typeName)
                    when (property.name) {
                        "isShared" -> {
                            parameter.defaultValue("false")
                            modelClassTypeSpec.addSuperinterface(sharedInterface)
                            parameter.addModifiers(KModifier.OVERRIDE)
                        }
                        "isFavorite" -> {
                            parameter.defaultValue("false")
                            modelClassTypeSpec.addSuperinterface(favoritableEntityInterface)
                            parameter.addModifiers(KModifier.OVERRIDE)
                        }
                        else -> parameter.defaultValue(default)
                    }
                    primaryConstructor.addParameter(parameter.build())
                    modelClassTypeSpec.addProperty(propertySpec)
                }
                modelClassTypeSpec.primaryConstructor(primaryConstructor.build())

                val entityAnnotationSpec = AnnotationSpec.builder(Entity::class)
                    .addMember("%L = %S", "tableName", modelName.snake())

                if (!linkModels.containsKey(modelName)) {
                    val fields = mutableProps.map { it.name }
                    if (!fields.contains("created") && !fields.contains("modified")) {
                        base = idEntityInterface
                    }
                    modelClassTypeSpec.addSuperinterface(base)
                } else {
                    entityAnnotationSpec.addMember("%L = [%S, %S]", "primaryKeys", *foreignKeys.map { it.snake() }.toTypedArray())
                }

                val indices = mutableListOf<AnnotationSpec>()
                val foreignKeysAnnotations = foreignKeys.map {
                    val parent = it
                    val resolvedParent = resolveEntityNameMatch(parent.substring(2), definition.name)
                    val entityClass = ClassName("$packageName.database.entity", resolvedParent)
                    indices.add(AnnotationSpec.builder(Index::class)
                        .addMember("%L = arrayOf(%S)", "value", it.snake())
                        .build())
                    AnnotationSpec.builder(ForeignKey::class)
                        .addMember("%L = %T::class", "entity", entityClass)
                        .addMember("\n%L = arrayOf(%S)", "parentColumns", "id_${resolvedParent.snake()}")
                        .addMember("\n%L = arrayOf(%S)", "childColumns", it.snake())
                        .addMember("\n%L = %T.CASCADE", "onDelete", ForeignKey::class)
                        .addMember("\n%L = %T.CASCADE", "onUpdate", ForeignKey::class)
                        .build()
                }
                if (foreignKeysAnnotations.isNotEmpty()) {
                    entityAnnotationSpec.addMember(foreignKeysAnnotations.joinToString(separator = ",\n", prefix = "%L = [\n", postfix = "\n]") { "%L" }, "foreignKeys", *foreignKeysAnnotations.toTypedArray())
                    entityAnnotationSpec.addMember(indices.joinToString(separator = ",\n", prefix = "%L = [\n", postfix = "\n]") { "%L" }, "indices", *indices.toTypedArray())
                }
                modelClassTypeSpec.addAnnotation(entityAnnotationSpec.build())

                databaseEntitiesTypeSpec.add(modelClassTypeSpec.build())
            }
        }

        return classNameList
    }

    private fun String.isFavoritableEntity(): Boolean {
        return when (this) {
            "Order", "Todo", "Appointment", "Company", "Person" -> true
            else -> false
        }
    }

    enum class DaoClass {
        ID,
        MUTABLE
    }

    private fun createDao(packageName: String) {
        val liveData = ClassName("androidx.lifecycle", "LiveData")
        for (definition in modLinks) {
            val baseDao = ClassName("de.juvopro.bms.database.dao.base", "BaseDao")
            val baseIdEntityDao = ClassName("de.juvopro.bms.database.dao.base", "BaseIdEntityDao")
            val baseEntityDao = ClassName("de.juvopro.bms.database.dao.base", "BaseEntityDao")
            val baseLinkDao = ClassName("de.juvopro.bms.database.dao.base", "BaseLinkDao")
            val entityName = definition.name

            if (skipSyncDataModels(entityName)) continue // skip

            val snakeCaseName = entityName.snake()
            val parameterType = ClassName("$packageName.database.entity", entityName)
            val modelClassTypeSpec = TypeSpec.interfaceBuilder(entityName + "BaseDao")
                .addAnnotation(AnnotationSpec.builder(Dao::class)
                    .build())

            if (models.containsKey(definition.name)) {
                if (definition is ObjectEntity) {
                    val type = if (definition.props.find { it.name == "modified" } != null) DaoClass.MUTABLE
                    else DaoClass.ID

                    modelClassTypeSpec.addFunction(FunSpec.builder("findById")
                        .addModifiers(KModifier.ABSTRACT, KModifier.OVERRIDE)
                        .returns(liveData.parameterizedBy(parameterType.asNullable()))
                        .addAnnotation(AnnotationSpec.builder(Query::class)
                            .addMember("%S", "SELECT * FROM `$snakeCaseName` WHERE id_$snakeCaseName = :id")
                            .build())
                        .addParameter("id", Int::class)
                        .build())

                    modelClassTypeSpec.addFunction(FunSpec.builder("findByIdSync")
                        .addModifiers(KModifier.ABSTRACT, KModifier.OVERRIDE)
                        .returns(parameterType.asNullable())
                        .addAnnotation(AnnotationSpec.builder(Query::class)
                            .addMember("%S", "SELECT * FROM `$snakeCaseName` WHERE id_$snakeCaseName = :id")
                            .build())
                        .addParameter("id", Int::class)
                        .build())

                    modelClassTypeSpec.addFunction(FunSpec.builder("findByIdIn")
                        .addModifiers(KModifier.ABSTRACT, KModifier.OVERRIDE)
                        .returns(liveData.parameterizedBy(List::class.asTypeName().parameterizedBy(parameterType)))
                        .addAnnotation(AnnotationSpec.builder(Query::class)
                            .addMember("%S", "SELECT * FROM `$snakeCaseName` WHERE id_$snakeCaseName IN (:id)")
                            .build())
                        .addParameter("id", List::class.parameterizedBy(Int::class))
                        .build())

                    modelClassTypeSpec.addFunction(FunSpec.builder("findByIdInSync")
                        .addModifiers(KModifier.ABSTRACT, KModifier.OVERRIDE)
                        .returns(List::class.asTypeName().parameterizedBy(parameterType))
                        .addAnnotation(AnnotationSpec.builder(Query::class)
                            .addMember("%S", "SELECT * FROM `$snakeCaseName` WHERE id_$snakeCaseName IN (:id)")
                            .build())
                        .addParameter("id", List::class.parameterizedBy(Int::class))
                        .build())

                    modelClassTypeSpec.addFunction(FunSpec.builder("findAll")
                        .addModifiers(KModifier.ABSTRACT, KModifier.OVERRIDE)
                        .returns(liveData.parameterizedBy(List::class.asTypeName().parameterizedBy(parameterType)))
                        .addAnnotation(AnnotationSpec.builder(Query::class)
                            .addMember("%S", "SELECT * FROM `$snakeCaseName`")
                            .build())
                        .build())

                    modelClassTypeSpec.addFunction(FunSpec.builder("findAllSync")
                        .addModifiers(KModifier.ABSTRACT, KModifier.OVERRIDE)
                        .returns(List::class.asTypeName().parameterizedBy(parameterType))
                        .addAnnotation(AnnotationSpec.builder(Query::class)
                            .addMember("%S", "SELECT * FROM `$snakeCaseName`")
                            .build())
                        .build())

                    if (type == DaoClass.MUTABLE) {
                        modelClassTypeSpec.addSuperinterface(baseEntityDao.parameterizedBy(parameterType))

                        modelClassTypeSpec.addFunction(FunSpec.builder("findSinceBefore")
                            .addModifiers(KModifier.ABSTRACT, KModifier.OVERRIDE)
                            .returns(List::class.asTypeName().parameterizedBy(parameterType))
                            .addAnnotation(AnnotationSpec.builder(Query::class)
                                .addMember("%S", "SELECT * FROM `$snakeCaseName` WHERE modified BETWEEN :since AND :before")
                                .build())
                            .addParameter("since", Date::class)
                            .addParameter("before", Date::class)
                            .build())
                    } else {
                        modelClassTypeSpec.addSuperinterface(baseIdEntityDao.parameterizedBy(parameterType))
                    }
                }

            } else {
                modelClassTypeSpec.addSuperinterface(baseDao.parameterizedBy(parameterType))
                modelClassTypeSpec.addSuperinterface(baseLinkDao.parameterizedBy(parameterType))

                val groupParam = "id_${snakeCaseName.substring(0, snakeCaseName.length - 5)}"
                modelClassTypeSpec.addFunction(FunSpec.builder("findByGroupId")
                    .addModifiers(KModifier.ABSTRACT)
                    .addModifiers(KModifier.OVERRIDE)
                    .returns(List::class.asTypeName().parameterizedBy(parameterType))
                    .addAnnotation(AnnotationSpec.builder(Query::class)
                        .addMember("%S", "SELECT * FROM `$snakeCaseName` WHERE $groupParam = :id")
                        .build())
                    .addParameter("id", Int::class)
                    .build())

                modelClassTypeSpec.addFunction(FunSpec.builder("findByGroupIdLive")
                    .addModifiers(KModifier.ABSTRACT)
                    //.addModifiers(KModifier.OVERRIDE)
                    .returns(liveData.parameterizedBy(List::class.asTypeName().parameterizedBy(parameterType)))
                    .addAnnotation(AnnotationSpec.builder(Query::class)
                        .addMember("%S", "SELECT * FROM `$snakeCaseName` WHERE $groupParam = :id")
                        .build())
                    .addParameter("id", Int::class)
                    .build())

                val param = groupParam.substring(0, groupParam.length - 6)
                modelClassTypeSpec.addFunction(FunSpec.builder("deleteOldLinks")
                    .addModifiers(KModifier.ABSTRACT)
                    //.addModifiers(KModifier.OVERRIDE)
                    .addAnnotation(AnnotationSpec.builder(Query::class)
                        .addMember("%S", "DELETE FROM `$snakeCaseName` WHERE $groupParam = :id AND $param NOT IN (:list)")
                        .build())
                    .addParameter("id", Int::class)
                    .addParameter("list", List::class.parameterizedBy(Int::class))
                    .build())
            }
            val baseType = modelClassTypeSpec.build()
            baseDaoTypeSpec.add(baseType)
            val daoClassTypeSpec = TypeSpec.interfaceBuilder(entityName + "Dao")
                .addAnnotation(AnnotationSpec.builder(Dao::class)
                    .build())
                .addSuperinterface(ClassName("$packageName.database.dao.base", entityName + "BaseDao"))
            daoTypeSpec.add(daoClassTypeSpec.build())
        }
        daoTypeSpec
    }

    private fun createDatabase(packageName: String) {
        val sorted = databaseEntitiesTypeSpec.sortedWith(compareBy { it.name })
        val literal = sorted.joinToString(separator = ",\n    ", prefix = "[\n", postfix = "\n]") {
            "${it.name}::class"
        }
        val room = ClassName("androidx.room", "RoomDatabase")

        val modelClassTypeSpec = TypeSpec.classBuilder("Database")
            .superclass(room)
            .addAnnotation(AnnotationSpec.builder(Database::class)
                .addMember("%L = %L", "entities", literal)
                .addMember("%L = %L", "version", 1)
                .build())
            .addModifiers(KModifier.ABSTRACT)

        val sortedDao = daoTypeSpec.sortedWith(compareBy { it.name })

        sortedDao.forEach {
            val name = it.name ?: "none"
            val type = ClassName("$packageName.database.dao", name)
            modelClassTypeSpec.addFunction(FunSpec.builder(name.decapitalize())
                .returns(type)
                .addModifiers(KModifier.ABSTRACT)
                .build())
        }

        val depends = FunSpec.builder("depend")
        depends.addCode("/*\n")
        sortedDao.forEach {
            val name = it.name ?: "none"
            depends.addStatement("single { get<BMSDatabase>().%L() }", name.decapitalize())
        }
        depends.addCode("\n*/")
        modelClassTypeSpec.addFunction(depends.build())
        databaseTypeSpec = modelClassTypeSpec.build()
    }

    private fun createMappers(packageName: String) {
        val classNameList = ArrayList<String>()

        for (definition in mods) {
            val entityName = definition.name

            if (skipSyncDataModels(entityName)) continue // skip

            val modelClassTypeSpec: TypeSpec.Builder = TypeSpec.classBuilder(entityName + "Mapper")
            classNameList.add(entityName)

            if (definition is ObjectEntity) {
                val syncEntity = ClassName("$packageName.sync.entity", entityName + syncDtoPostfix)
                val databaseEntity = ClassName("$packageName.database.entity", entityName)

                val parameters = definition.props.filter {
                    if (it is ArrayProp) {
                        val type = it.type
                        return@filter (type is StringProp
                            || type is DateProp
                            || type is EnumProp)
                    }
                    return@filter it !is ArrayProp || it !is ObjectProp
                }
                val statementFrom = parameters.joinToString(separator = ",\n    ", prefix = "(\n    ", postfix = "\n)") {
                    var param = it.name
                    if (it is ArrayProp) {
                        if (it.type is EnumProp) {
                            val name = it.type.enumName
                            val enum = swaggerModel.components.schemas[name]?.enum?.apply { remove("none") } ?: listOf()
                            enums2[name] = enum as List<String>
                            return@joinToString "$param = entity.$param.map { it.toString() }"
                        }
                    }
                    if (it is EnumProp) {
                        val name = it.enumName
                        val enum = swaggerModel.components.schemas[name]?.enum?.apply { remove("none") } ?: listOf()
                        enums2[name] = enum as List<String>
                        return@joinToString "$param = entity.$param?.toString()"
                    }
                    if (it is ObjectProp) {
                        param = "id${param.capitalize()}"
                        return@joinToString "//$param = entity.${it.name}?.id"
                    }
                    return@joinToString "$param = entity.${it.name}"
                }
                val classes = mutableListOf<TypeName>()
                val mapped = parameters.map {
                    val param = it.name
                    if (it is ArrayProp) {
                        if (it.type is EnumProp) {
                            classes.add(ClassName("de.juvopro.bms.base.model", it.type.enumName))
                            return@map "$param = entity.$param.mapNotNull { %T.from(it) }.toSet()"
                        }
                    }
                    if (it is EnumProp) {
                        classes.add(ClassName("de.juvopro.bms.base.model", it.enumName))
                        return@map "$param = entity.$param?.let { %T.from(it) }"
                    }
                    if (it is ObjectProp) {
                        return@map null
                    }
                    return@map "${it.name} = entity.$param"
                }.filterNotNull().let { if (definition.name == "PersonGroup") it.toMutableList().apply { add("type = type") } else it }

                val statement = mapped.joinToString(separator = ",\n    ", prefix = "(\n    ", postfix = "\n)")
                val block = CodeBlock.of(statement, *classes.toTypedArray())

                val funcFromSyncToDatabase = FunSpec.builder("map")
                    .addParameter("entity", syncEntity)
                    .addStatement("return %T%L", databaseEntity, block)
                if (definition.name == "PersonGroup") {
                    funcFromSyncToDatabase.addParameter("type", String::class)
                }

                val funcFromDatabaseToSync = FunSpec.builder("map")
                    .addParameter("entity", databaseEntity)
                    .addStatement("return %T%L", syncEntity, statementFrom)
                    .build()

                modelClassTypeSpec.addFunction(funcFromSyncToDatabase.build())
                modelClassTypeSpec.addFunction(funcFromDatabaseToSync)
                mappersTypeSpec.add(modelClassTypeSpec.build())
            }
        }
        mappersTypeSpec
    }

    private fun createEnums(packageName: String) {
        val r = ClassName(packageName, "R")
        enums2.forEach { name, list ->
            val spec = TypeSpec.enumBuilder(name)
            list.forEachIndexed { index, n ->
                val enumName = n.replace(" ", "_")
                val stringName = when (enumName) {
                    "public", "private", "void", "new" -> enumName + "_text"
                    else -> enumName
                }
                val s = TypeSpec.anonymousClassBuilder()
                    .addSuperclassConstructorParameter("%S", n)
                    .addSuperclassConstructorParameter("%T.string.$stringName", r)
                val extra = extraFields[name]
                extra?.let { e ->
                    val value = e.second[index]
                    if (value is RRequire) {
                        s.addSuperclassConstructorParameter(value.text, r)
                    } else s.addSuperclassConstructorParameter("%S", value.text)
                }
                spec.addEnumConstant(enumName.toUpperCase(), s.build())
            }
            val primaryConstructor = FunSpec.constructorBuilder()
            primaryConstructor.addParameter(ParameterSpec.builder("value", String::class).build())
            primaryConstructor.addParameter(ParameterSpec.builder("resId", Int::class).build())
            val extra = extraFields[name]
            extra?.let { e ->
                primaryConstructor.addParameter(ParameterSpec.builder(e.first, e.third).build())
            }
            spec.addProperty(PropertySpec.builder("value", String::class)
                .initializer("value")
                .build())
            spec.addProperty(PropertySpec.builder("resId", Int::class)
                .initializer("resId")
                .build())
            extra?.let { e ->
                spec.addProperty(PropertySpec.builder(e.first, e.third)
                    .initializer(e.first)
                    .build())
            }
            spec.addFunction(FunSpec.builder("toString")
                .addModifiers(KModifier.OVERRIDE)
                .returns(String::class)
                .addStatement("return value")
                .build())
            val companion = TypeSpec.companionObjectBuilder()
                .addFunction(FunSpec.builder("from")
                    .addParameter("s", String::class.asTypeName().asNullable())
                    .returns(ClassName("", name).asNullable())
                    .addStatement("return values().find { it.value == s }")
                    .build())
                .build()
            spec.primaryConstructor(primaryConstructor.build())
            spec.addType(companion)
            enumListTypeSpec.add(spec.build())
        }
        enumListTypeSpec
    }

    open class Specs(val text: String)
    class RRequire(text: String) : Specs(text)
    class SimpleSpecs(text: String) : Specs(text)

    private val extraFields = mapOf<String, Triple<String, List<Specs>, KClass<*>>>(
        "BurialMethods" to Triple("iconResId",
            listOf(
                RRequire("%T.drawable.ic_cremation"),
                RRequire("%T.drawable.ic_earth_burial"),
                RRequire("%T.drawable.ic_earth_burial"),
                RRequire("%T.drawable.ic_reburial"),
                RRequire("%T.drawable.ic_salvage")
            ),
            Int::class),
        "OrderStatuses" to Triple("iconResId",
            listOf(
                RRequire("%T.drawable.ic_status_0"),
                RRequire("%T.drawable.ic_status_4"),
                RRequire("%T.drawable.ic_status_2"),
                RRequire("%T.drawable.ic_status_3"),
                RRequire("%T.drawable.ic_status_1")
            ),
            Int::class),
        "Languages" to Triple("locale",
            listOf(
                SimpleSpecs("en"),
                SimpleSpecs("de"),
                SimpleSpecs("pl")
            ),
            String::class),
        "ExternalStatuses" to Triple("iconResId",
            listOf(
                RRequire("%T.drawable.ic_status_0"),
                RRequire("%T.drawable.ic_status_2"),
                RRequire("%T.drawable.ic_status_3")
            ),
            Int::class)
    )

    private fun createMapHelper(packageName: String) {
        val syncParams = ClassName("$packageName.sync.content", "GetContent").nestedClass("SyncParams")

        val ttt = Map::class.asTypeName()
            .parameterizedBy(Int::class.asTypeName(), syncParams)

        val modelClassTypeSpec = TypeSpec.classBuilder("MapHelper")
            .primaryConstructor(FunSpec.constructorBuilder()
                .addParameter("guid", Map::class.asTypeName()
                    .parameterizedBy(String::class.asTypeName(), ttt))
                .build())
            .addModifiers(KModifier.OPEN)

        val map = mutableSetOf<String>()

        mods.forEach { entry ->
            val name = entry.name
            if (skipSyncDataModels(name)) return@forEach // skip
            if (name == "PersonGroup") return@forEach
            val entity = ClassName("$packageName.sync.entity", name + syncDtoPostfix)
            val mapper = ClassName("$packageName.sync.mapper", name + "Mapper")
            val returnEntity = ClassName("$packageName.database.entity", name)

            val paramName = name.decapitalize()

            val f = FunSpec.builder("map")
                .addParameter(paramName, entity)
                .addParameter("mapper", mapper)
                .returns(returnEntity)

            if (entry.name == "Translation") {
                f.addModifiers(KModifier.OPEN)
            }

            f.addStatement("val entity = mapper.map(%L)", paramName)

            if (entry is ObjectEntity) {
                entry.props.forEach { p ->
                    recursive(p, f, paramName) { prop, paramName ->
                        val fieldName = if (prop.name == "id") {
                            prop.name + name
                        } else {
                            prop.name
                        }
                        val field = fieldName.substring(2)
                        val mapName = resolveMapHelperMatch(field, name).decapitalize() + "Id"
                        map.add(mapName)
                        f.addStatement("entity.%L = %L[entity.%L]?.localId", prop.name, mapName, prop.name)
                    }
                }
            }

            f.addStatement("return entity")

            modelClassTypeSpec.addFunction(f.build())
        }

        map.forEach {
            val name = it.substring(0, it.length - 2).snake()
            modelClassTypeSpec.addProperty(PropertySpec.builder(it, ttt)
                .initializer("guid[%S]?: mapOf()", name)
                .build())
        }

        mapHelperTypeSpec = modelClassTypeSpec.build()
    }

    private fun recursive(prop: Prop, f: FunSpec.Builder, paramName: String, func: (Prop, String) -> Unit) {
        when (prop) {
            is IntegerProp -> {
                if (prop.name.startsWith("id")) {
                    func(prop, paramName)
                }
            }
            /*is ObjectProp -> {
                f.addStatement("$paramName.${prop.name}?.let {")
                mods.find { it.name == prop.objectName }?.let {
                    if (it is ObjectEntity) {
                        it.props.forEach { recursive(it, f, "it", func) }
                    }
                }
                f.addStatement("}")
            }
            is ArrayProp -> {
                if (prop.type is ObjectProp) {
                    f.addStatement("$paramName.${prop.name}.forEach {")
                    mods.find { it.name == prop.type.objectName }?.let {
                        if (it is ObjectEntity) {
                            it.props.forEach { recursive(it, f, "it", func) }
                        }
                    }
                    f.addStatement("}")
                }
            }*/
        }
    }

    private fun resolveEntityNameMatch(field: String, entity: String): String {
        return when (field) {
            "PersonGroupContact" -> "PersonGroup"
            "ReportTemplate" -> "Report"
            "Payer" -> "Contact"
            else -> resolveMapHelperMatch(field, entity)
        }
    }

    private fun resolveMapHelperMatch(field: String, entity: String): String {
        if (field.startsWith("ContactUnit")) {
            return "ContactUnit"
        }
        if (field.startsWith("Person")) {
            if (field == "PersonGroup" || field == "PersonCategory") return field
            return "Person"
        }
        return when (field) {
            "Taxinfo" -> "TaxInfo"
            "ContactPerson", "PersonCreator", "CreatorPerson" -> "Person"
            "SecondPayer" -> "Payer"
            "Type" -> "ItemType"
            "Category" -> if (entity == "ItemCategoryType") "ItemCategory" else "Category"
            "ContactFrom" -> "Contact"
            "ReceiptFrom" -> "Receipt"
            "HeadOfficeCompany", "CompanyRegister" -> "Company"
            "OrderOriginatedFrom" -> "Order"
            "CompanyBirthRegistryOffice" -> "Company"
            "CompanyDeathPlaceRegistryOffice" -> "Company"
            "AddresOfDeath" -> "Company"
            "CompanyChurch" -> "Company"
            "PersonGroupContact" -> "PersonGroup"

            else -> field
        }
    }

    private fun skipSyncDataModels(name: String): Boolean =
        name == "SyncData"
            || name == "SyncMap"
            || name == "PermissionSyncInfo"
            || name == "OrderCopyConfig"
            || name == "OrderConfig"

    private fun createPutMapHelper(packageName: String) {
        val ttt = ClassName("", "MutableSet")
            .parameterizedBy(Int::class.asTypeName().asNullable())

        val modelClassTypeSpec = TypeSpec.classBuilder("PutMapHelper")
            .addModifiers(KModifier.OPEN)

        val map = mutableSetOf<String>()

        mods.forEach { entry ->
            val name = entry.name
            if (skipSyncDataModels(name)) return@forEach // skip
            val syncEntity = ClassName("$packageName.sync.entity", name + syncDtoPostfix)
            val mapper = ClassName("$packageName.sync.mapper", name + "Mapper")
            val databaseEntity = ClassName("$packageName.database.entity", name)

            val f = FunSpec.builder("map")
                .addParameter(name.decapitalize(), databaseEntity)
                .addParameter("mapper", mapper)
                .returns(syncEntity)

            if (entry.name == "Translation") {
                f.addModifiers(KModifier.OPEN)
            }

            if (entry is ObjectEntity) {
                entry.props.forEach {
                    if (it.name.startsWith("id") && it is IntegerProp) {
                        val databaseFieldName = if (it.name == "id") {
                            it.name + name
                        } else {
                            it.name
                        }
                        val field = databaseFieldName.substring(2)
                        val mapName = resolveMapHelperMatch(field, name).decapitalize() + "Id"
                        map.add(mapName)
                        f.addStatement("%L.add(%L.%L)", mapName, name.decapitalize(), it.name)
                    }
                }
            }

            f.addStatement("return mapper.map(%L)", name.decapitalize())

            modelClassTypeSpec.addFunction(f.build())
        }

        map.forEach {
            modelClassTypeSpec.addProperty(PropertySpec.builder(it, ttt)
                .initializer("mutableSetOf()")
                .build())
        }

        mapPutHelperTypeSpec = modelClassTypeSpec.build()
    }

    val enums = listOf(
        "externalStatus",
        "internalStatus",
        "orderType",
        "itemType",
        "itemCategory",
        "contactType",
        "receiptStatus",
        "receiptType",
        "unit",
        "burialMethod",
        "status",
        "reportType",
        "linkageType",
        "salutation",
        "dyingStyle",
        "customerType",
        "burialCondition"
    )

    private fun findDefinition(name: String): Schema<Any> {
        return swaggerModel.components.schemas[name] ?: throw IllegalArgumentException()
    }

    private fun findRefs(model: Schema<Any>, types: ArrayList<String>) {
        model.properties?.forEach { _, property ->
            if (property.`$ref` != null) {
                val ref = RefUtils.computeDefinitionName(property.`$ref`)
                if (!types.contains(ref)) types.add(ref)
            }
            if (property.type == ARRAY_SWAGGER_TYPE) {
                val arrayProperty = property as ArraySchema?
                val items = arrayProperty?.items
                if (items?.`$ref` != null) {
                    val ref = RefUtils.computeDefinitionName(items.`$ref`)
                    if (!types.contains(ref)) types.add(ref)
                }
            }
        }
    }

    private fun createUndoTriggers(packageName: String) {
        val modelClassTypeSpec = TypeSpec.classBuilder("TriggersHelper")
        val insertTriggers = mutableListOf<String>()
        val updateTriggers = mutableListOf<String>()
        val deleteTriggers = mutableListOf<String>()
        val deleteTriggerCommands = mutableListOf<String>()

        for (definition in modLinks) {
            val modelName = definition.name
            if (skipSyncDataModels(modelName)) continue // skip
            if (definition is ObjectEntity) {
                val primaryConstructor = FunSpec.constructorBuilder()
                val mutableProps = definition.props.toMutableList()
                if (!definition.name.contains("Link") && !mutableProps.map { it.name }.contains("id")) {
                    mutableProps.add(IntegerProp("id"))
                }
                if (definition.name == "PersonGroup") {
                    mutableProps.add(StringProp("type"))
                }
                val props = mutableListOf<String>()
                for (property in mutableProps) {
                    if (property is ArrayProp && property.type !is StringProp
                        && property.type !is DateProp
                        && property.type !is EnumProp) {
                        continue
                    }
                    var databasePropertyName = property.name
                    if (property is ObjectProp) {
                        databasePropertyName = "id${databasePropertyName.capitalize()}"
                    }
                    if (property.name == "id") {
                        databasePropertyName += modelName
                    }
                    props.add(databasePropertyName.snake())
                }

                val tableName = modelName.snake()
                val insertTrigger = "CREATE TEMP TRIGGER ${tableName}_it AFTER INSERT ON `$tableName` BEGIN\n" +
                    "  INSERT INTO undolog VALUES(NULL,'DELETE FROM `$tableName` WHERE rowid='||new.rowid);\n" +
                    "END;"
                insertTriggers.add(insertTrigger)

                val updateTriggerBuilder = StringBuilder()
                val updateStart = "CREATE TEMP TRIGGER ${tableName}_ut AFTER UPDATE ON `$tableName` BEGIN\n" +
                    "  INSERT INTO undolog VALUES(NULL,'UPDATE `$tableName`\n" +
                    "     SET "
                val updateEnding = "\n" +
                    "   WHERE rowid='||old.rowid);\n" +
                    "END;"

                updateTriggerBuilder.append(updateStart)

                updateTriggerBuilder.append(
                    props.joinToString { "$it='||quote(old.$it)||'" }
                )

                updateTriggerBuilder.append(updateEnding)
                val updateTrigger = updateTriggerBuilder.toString()
                updateTriggers.add(updateTrigger)

                val deleteTriggerBuilder = StringBuilder()

                /*"CREATE TEMP TRIGGER ${tableName}_dt BEFORE DELETE ON $tableName BEGIN\n" +
                    "  INSERT INTO undolog VALUES(NULL,'INSERT INTO $tableName(rowid,a,b,c)\n" +
                    "    VALUES('||old.rowid||','||quote(old.a)||','||quote(old.b)||\n" +
                    "           ','||quote(old.c)||')');\n" +
                    "END;"*/

                val deleteStart = "CREATE TEMP TRIGGER ${tableName}_dt BEFORE DELETE ON `$tableName` BEGIN\n" +
                    "  INSERT INTO undolog VALUES(NULL,'INSERT INTO `$tableName`("

                deleteTriggerBuilder.append(deleteStart).append("rowid").append(",")
                deleteTriggerBuilder.append(
                    props.joinToString { it }
                )
                deleteTriggerBuilder.append(")\n")

                deleteTriggerBuilder.append("    VALUES(").append("'||old.rowid||'").append(",")
                deleteTriggerBuilder.append(
                    props.joinToString { "'||quote(old.$it)||'" }
                )
                deleteTriggerBuilder.append(")');\nEND;")

                val deleteTrigger = deleteTriggerBuilder.toString()
                deleteTriggers.add(deleteTrigger)

                deleteTriggerCommands.add("DROP TRIGGER ${tableName}_it;")
                deleteTriggerCommands.add("DROP TRIGGER ${tableName}_ut;")
                deleteTriggerCommands.add("DROP TRIGGER ${tableName}_dt;")
            }
        }

        val allTriggers = insertTriggers + updateTriggers + deleteTriggers

        modelClassTypeSpec.addProperty(PropertySpec.builder("triggers", List::class.parameterizedBy(String::class))
            .initializer(allTriggers.joinToString(separator = ",\n", prefix = "listOf(\n", postfix = "\n)", transform = { "%S" }),
                *allTriggers.toTypedArray())
            .build())
        modelClassTypeSpec.addProperty(PropertySpec.builder("dropTriggersCommands", List::class.parameterizedBy(String::class))
            .initializer(deleteTriggerCommands.joinToString(separator = ",\n", prefix = "listOf(\n", postfix = "\n)", transform = { "%S" }),
                *deleteTriggerCommands.toTypedArray())
            .build())

        triggersHelperTypeSpec = modelClassTypeSpec.build()

        insertTriggers
        updateTriggers
        deleteTriggers
        deleteTriggerCommands
    }

    private fun createApiRetrofitInterface(classNameList: List<String>): TypeSpec {
        val apiInterfaceTypeSpecBuilder = TypeSpec
            .interfaceBuilder("${proteinApiConfiguration.componentName}ApiInterface")
            .addModifiers(KModifier.PUBLIC)

        addApiPathMethods(apiInterfaceTypeSpecBuilder, classNameList)

        return apiInterfaceTypeSpecBuilder.build()
    }

    private fun addApiPathMethods(apiInterfaceTypeSpec: TypeSpec.Builder, classNameList: List<String>) {
        /*if (swaggerModel.paths != null && !swaggerModel.paths.isEmpty()) {
            for (path in swaggerModel.paths) {
                for (operation in path.value.operationMap) {

                    val annotationSpec: AnnotationSpec = when {
                        operation.key.name.contains(
                            "GET") -> AnnotationSpec.builder(GET::class).addMember("\"${path.key}\"").build()
                        operation.key.name.contains(
                            "POST") -> AnnotationSpec.builder(POST::class).addMember("\"${path.key}\"").build()
                        operation.key.name.contains(
                            "PUT") -> AnnotationSpec.builder(PUT::class).addMember("\"${path.key}\"").build()
                        operation.key.name.contains(
                            "PATCH") -> AnnotationSpec.builder(PATCH::class).addMember("\"${path.key}\"").build()
                        operation.key.name.contains(
                            "DELETE") -> AnnotationSpec.builder(DELETE::class).addMember("\"${path.key}\"").build()
                        else -> AnnotationSpec.builder(GET::class).addMember("\"${path.key}\"").build()
                    }

                    try {
                        val doc = ((listOf(operation.value.summary + "\n") + getMethodParametersDocs(operation)).joinToString("\n")).trim()

                        val returnedClass = getReturnedClass(operation, classNameList)
                        val methodParameters = getMethodParameters(operation)
                        val funSpec = FunSpec.builder(operation.value.operationId)
                            .addModifiers(KModifier.PUBLIC, KModifier.ABSTRACT)
                            .addAnnotation(annotationSpec)
                            .addParameters(methodParameters)
                            .returns(returnedClass)
                            .addKdoc("$doc\n")
                            .build()

                        apiInterfaceTypeSpec.addFunction(funSpec)
                    } catch (exception: Exception) {
                        errorTracking.logException(exception)
                    }
                }
            }
        }*/
    }

    private fun getMethodParametersDocs(operation: MutableMap.MutableEntry<HttpMethod, Operation>): Iterable<String> {
        return operation.value.parameters.filterNot { it.description.isNullOrBlank() }.map { "@param ${it.name} ${it.description}" }
    }

    val enums2: MutableMap<String, List<String>> = mutableMapOf()

    //private fun

    private fun getTypeName(modelProperty: MutableMap.MutableEntry<String, Schema<Any>>, isDto: Boolean = false): TypeName {
        val property = modelProperty.value
        return when {
            property.`$ref` != null -> {
                val refName = RefUtils.computeDefinitionName(property.`$ref`)
                val definition = swaggerModel.components.schemas[refName]
                if (definition != null) {
                    when (definition.type) {
                        "string" -> {
                            if (definition.enum != null) {
                                @Suppress("UNCHECKED_CAST")
                                enums2[refName] = definition.enum as List<String>
                            }
                            return TypeVariableName(String::class.simpleName!!).requiredOrNullable(property.nullable)
                        }
                    }
                }
                TypeVariableName.invoke(refName).requiredOrNullable(property.nullable)
            }

            property.type == ARRAY_SWAGGER_TYPE -> {
                val arrayProperty = property as ArraySchema
                getTypedArray(arrayProperty.items, isDto).requiredOrNullable(arrayProperty.nullable)
            }
            else -> getKotlinClassTypeName(property).requiredOrNullable(property.nullable)
        }
    }

    private fun getMethodParameters(
        operation: MutableMap.MutableEntry<HttpMethod, Operation>
    ): Iterable<ParameterSpec> {
        return linkedSetOf()
        /*return operation.value.parameters.mapNotNull { parameter ->
            // Transform parameters in the format foo.bar to fooBar
            val name = parameter.name.split('.').mapIndexed { index, s -> if (index > 0) s.capitalize() else s }.joinToString("")
            when (parameter.`in`) {
                "body" -> {
                    ParameterSpec.builder(name, getBodyParameterSpec(parameter))
                        .addAnnotation(AnnotationSpec.builder(Body::class).build()).build()
                }
                "path" -> {
                    val type = getKotlinClassTypeName((parameter as PathParameter).type, parameter.format).requiredOrNullable(parameter.required)
                    ParameterSpec.builder(name, type)
                        .addAnnotation(AnnotationSpec.builder(Path::class).addMember("\"${parameter.name}\"").build()).build()
                }
                "query" -> {
                    if ((parameter as QueryParameter).type == ARRAY_SWAGGER_TYPE) {
                        val type = List::class.asClassName().parameterizedBy(getKotlinClassTypeName(parameter.items.type)).requiredOrNullable(parameter.required)
                        ParameterSpec.builder(name, type)
                    } else {
                        val type = getKotlinClassTypeName(parameter.type, parameter.format).requiredOrNullable(parameter.required)
                        ParameterSpec.builder(name, type)
                    }.addAnnotation(AnnotationSpec.builder(Query::class).addMember("\"${parameter.name}\"").build()).build()
                }
                else -> null
            }
        }*/
    }

    private fun getBodyParameterSpec(parameter: Parameter): TypeName {
        val bodyParameter = parameter as BodyParameter
        val schema = bodyParameter.schema

        return when (schema) {
            is RefModel -> ClassName.bestGuess(schema.simpleRef.capitalize()).requiredOrNullable(parameter.required)

            //is ArrayModel -> getTypedArray(schema.items).requiredOrNullable(parameter.required)

            else -> {
                val bodyParameter1 = parameter.schema as? ModelImpl ?: ModelImpl()

                if (STRING_SWAGGER_TYPE == bodyParameter1.type) {
                    String::class.asClassName().requiredOrNullable(parameter.required)
                } else {
                    ClassName.bestGuess(parameter.name.capitalize()).requiredOrNullable(parameter.required)
                }
            }
        }
    }

    private fun getTypedArray(items: Schema<*>, isDto: Boolean = false): TypeName {
        val typeProperty = when (items) {
            is LongProperty -> TypeVariableName.invoke(Long::class.simpleName!!)
            is IntegerSchema -> TypeVariableName.invoke(Int::class.simpleName!!)
            is FloatProperty -> TypeVariableName.invoke(Float::class.simpleName!!)
            is DoubleProperty -> TypeVariableName.invoke(Float::class.simpleName!!)
            is StringSchema -> TypeVariableName.invoke(String::class.simpleName!!)
            else -> getKotlinClassTypeName(items, isDto)
        }
        return List::class.asClassName().parameterizedBy(typeProperty)
    }

    private fun TypeName.requiredOrNullable(nullable: Boolean?) = if (nullable == false) this else asNullable()

    private fun getReturnedClass(
        operation: MutableMap.MutableEntry<HttpMethod, Operation>,
        classNameList: List<String>
    ): TypeName {
        try {
            if (operation.value.responses[OK_RESPONSE]?.schema != null &&
                operation.value.responses[OK_RESPONSE]?.schema is RefProperty) {
                val refProperty = (operation.value.responses[OK_RESPONSE]?.schema as RefProperty)
                var responseClassName = refProperty.simpleRef
                responseClassName = getValidClassName(responseClassName, refProperty)

                if (classNameList.contains(responseClassName)) {
                    return Single::class.asClassName().parameterizedBy(TypeVariableName.invoke(responseClassName))
                }
            } else if (operation.value.responses[OK_RESPONSE]?.schema != null &&
                operation.value.responses[OK_RESPONSE]?.schema is ArrayProperty) {
                val refProperty = (operation.value.responses[OK_RESPONSE]?.schema as ArrayProperty)
                var responseClassName = (refProperty.items as RefProperty).simpleRef
                responseClassName = getValidClassName(responseClassName, (refProperty.items as RefProperty))

                if (classNameList.contains(responseClassName)) {
                    return Single::class.asClassName().parameterizedBy(
                        List::class.asClassName().parameterizedBy(TypeVariableName.invoke(responseClassName))
                    )
                }
            }
        } catch (error: ClassCastException) {
            errorTracking.logException(error)
        }

        return Completable::class.asClassName()
    }

    private fun getValidClassName(responseClassName: String, refProperty: RefProperty): String {
        var className = responseClassName
        try {
            TypeSpec.classBuilder(className)
        } catch (error: IllegalArgumentException) {
            if (refProperty.simpleRef != null) {
                className = "Model" + refProperty.simpleRef.capitalize()
            }
        }
        return className
    }

    private fun getKotlinClassTypeName(schema: Schema<*>, isDto: Boolean = false): TypeName {
        val type = schema.type
        val format = schema.format
        if (schema.`$ref` != null) {
            val name = RefUtils.computeDefinitionName(schema.`$ref`)
            if (swaggerModel.components.schemas[name]?.type == "string") {
                return TypeVariableName(String::class.simpleName!!)
            }
            return TypeVariableName.invoke(name)
        }
        return when (type) {
            ARRAY_SWAGGER_TYPE -> TypeVariableName.invoke(List::class.simpleName!!)
            STRING_SWAGGER_TYPE -> {
                when (format) {
                    "date-time" -> Date::class.asTypeName()
                    else -> TypeVariableName.invoke(String::class.simpleName!!)
                }
            }
            NUMBER_SWAGGER_TYPE -> TypeVariableName.invoke(Float::class.simpleName!!)
            INTEGER_SWAGGER_TYPE -> {
                when (format) {
                    "int64" -> TypeVariableName.invoke(Long::class.simpleName!!)
                    else -> TypeVariableName.invoke(Int::class.simpleName!!)
                }
            }
            else -> TypeVariableName.invoke(type?.capitalize()?: "")
        }
    }

    /*private fun getPropertyInitializer(type: String): String {
        return when (type) {
            ARRAY_SWAGGER_TYPE -> "ArrayList()"
            INTEGER_SWAGGER_TYPE -> "0"
            STRING_SWAGGER_TYPE -> "\"\""
            BOOLEAN_SWAGGER_TYPE -> "false"
            else -> "null"
        }
    }*/

    fun getGeneratedApiInterfaceString(): String {
        return ""//StorageUtils.generateString(proteinApiConfiguration.packageName, apiInterfaceTypeSpec)
    }

    fun getGeneratedModelsString(): String {
        var generated = ""
        for (typeSpec in syncEntityModelListTypeSpec) {
            generated += StorageUtils.generateString(proteinApiConfiguration.packageName, typeSpec)
        }
        return generated
    }

    fun getGeneratedEnums(): String {
        var generated = ""
        for (typeSpec in enumListTypeSpec) {
            generated += StorageUtils.generateString(proteinApiConfiguration.packageName, typeSpec)
        }
        return generated
    }

    open class ModelEntity(val name: String)
    class ObjectEntity(name: String, val props: List<Prop>) : ModelEntity(name) {
        override fun toString(): String {
            return "Object. Name: $name, Properties: [ ${props.joinToString { it.name }} ]"
        }
    }
    class EnumEntity(name: String, val values: List<String>) : ModelEntity(name)

    open class Prop(val name: String) {
        val nullable: Boolean = true

        override fun toString(): String {
            return "Property. Name: $name"
        }
    }
    class IntegerProp(name: String) : Prop(name)
    class StringProp(name: String) : Prop(name)
    class DateProp(name: String) : Prop(name)
    class BoolProp(name: String) : Prop(name)
    class FloatProp(name: String) : Prop(name)
    class EnumProp(name: String, val enumName: String) : Prop(name)
    class ArrayProp(name: String, val type: Prop) : Prop(name)
    class ObjectProp(name: String, val objectName: String) : Prop(name)

    fun convert(objects: Map<String, Schema<Any>>): List<ModelEntity> {
        val models = mutableListOf<ModelEntity>()
        objects.forEach { def ->
            val name = def.key
            val schema = def.value
            if (schema.type == "object") {
                val model = ObjectEntity(
                    name,
                    schema.properties.map { p ->
                        val propName = p.key
                        val propScheme = p.value
                        getProp(propName, propScheme)
                    }
                )
                models.add(model)
            }
        }
        return models
    }

    fun getProp(name: String, schema: Schema<*>): Prop {
        return when(schema.type) {
            "string" -> {
                if (schema.format == "date-time") {
                    DateProp(name)
                } else StringProp(name)
            }
            "integer" -> IntegerProp(name)
            "boolean" -> BoolProp(name)
            "number" -> FloatProp(name)
            "array" -> ArrayProp(name, getProp(name, (schema as ArraySchema).items))
            else -> schema.`$ref`?.let { parseReference(name, it) } ?: Prop(name)
        }
    }

    fun parseReference(name: String, ref: String): Prop {
        val objects = swaggerModel.components.schemas
        val refName = RefUtils.computeDefinitionName(ref).removeSuffix()
        return if (objects[refName]?.enum != null) {
            EnumProp(name, refName)
        } else ObjectProp(name, refName)
    }

    fun getType(prop: Prop): TypeName {
        val typeName: TypeName = when(prop) {
            is StringProp -> String::class.asTypeName()
            is IntegerProp -> Int::class.asTypeName()
            is DateProp -> Date::class.asTypeName()
            is BoolProp -> Boolean::class.asTypeName()
            is FloatProp -> Float::class.asTypeName()
            is ArrayProp -> List::class.asTypeName().parameterizedBy(getType(prop.type))
            is EnumProp -> ClassName("", prop.enumName)
            is ObjectProp -> ClassName("", prop.objectName)
            else -> Object::class.asTypeName()
        }
        return typeName
    }

    private fun filterModels() {
        if (swaggerModel.components.schemas != null && swaggerModel.components.schemas.isNotEmpty()) {
            val types = arrayListOf<String>()
            types.add("SyncData")
            var i = 0
            while (i < types.size) {
                val name = types[i]
                val definition = findDefinition(name)
                val key = name.removeSuffix()
                models[key] = definition
                if (key.contains("Group") && key != "PriceGroupTypes") {
                    val link = ObjectSchema()
                    val prop = IntegerSchema()
                    prop.nullable = false
                    val withoutGroup = key.replace("Group", "")
                    link.properties = mutableMapOf()
                    link.properties["id$withoutGroup"] = prop
                    link.properties["id$key"] = prop
                    linkModels[key + "Link"] = link
                }
                findRefs(definition, types)
                i++
            }
        }
        modelsWithLinks.putAll(models)
        modelsWithLinks.putAll(linkModels)
    }

    private fun String.removeSuffix(): String {
        return replace(Regex("SyncDto\\b|Dto\\b"), "")
    }
}
