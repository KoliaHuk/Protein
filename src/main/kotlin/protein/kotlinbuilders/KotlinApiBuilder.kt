package protein.kotlinbuilders

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Query
import com.google.gson.annotations.SerializedName
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
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
import io.reactivex.Flowable
import io.reactivex.Single
import io.swagger.models.HttpMethod
import io.swagger.models.ModelImpl
import io.swagger.models.Operation
import io.swagger.models.RefModel
import io.swagger.models.ArrayModel
import io.swagger.models.Model
import io.swagger.models.Swagger
import io.swagger.models.parameters.BodyParameter
import io.swagger.models.parameters.Parameter
import io.swagger.models.parameters.PathParameter
import io.swagger.models.parameters.QueryParameter
import io.swagger.models.properties.ArrayProperty
import io.swagger.models.properties.DoubleProperty
import io.swagger.models.properties.FloatProperty
import io.swagger.models.properties.IntegerProperty
import io.swagger.models.properties.LongProperty
import io.swagger.models.properties.Property
import io.swagger.models.properties.RefProperty
import io.swagger.models.properties.StringProperty
import io.swagger.parser.SwaggerParser
import protein.common.StorageUtils
import protein.extensions.snake
import protein.tracking.ErrorTracking
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import java.io.FileNotFoundException
import java.lang.IllegalStateException
import java.net.UnknownHostException
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.LinkedHashSet

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

    private val swaggerModel: Swagger = try {
        if (!proteinApiConfiguration.swaggerUrl.isEmpty()) {
            SwaggerParser().read(proteinApiConfiguration.swaggerUrl)
        } else {
            SwaggerParser().read(proteinApiConfiguration.swaggerFile)
        }
    } catch (unknown: UnknownHostException) {
        errorTracking.logException(unknown)
        Swagger()
    } catch (illegal: IllegalStateException) {
        errorTracking.logException(illegal)
        Swagger()
    } catch (notFound: FileNotFoundException) {
        errorTracking.logException(notFound)
        Swagger()
    }

    private lateinit var apiInterfaceTypeSpec: TypeSpec
    private val models: MutableMap<String, Model> = mutableMapOf()
    private val responseBodyModelListTypeSpec: ArrayList<TypeSpec> = ArrayList()
    private val databaseEntitiesTypeSpec: ArrayList<TypeSpec> = ArrayList()
    private val daoTypeSpec: ArrayList<TypeSpec> = ArrayList()
    private lateinit var databaseTypeSpec: TypeSpec
    private lateinit var mapeHelperTypeSpec: TypeSpec
    private lateinit var mapPutHelperTypeSpec: TypeSpec
    private val mappersTypeSpec: ArrayList<TypeSpec> = ArrayList()
    private val enumListTypeSpec: ArrayList<TypeSpec> = ArrayList()

    fun build() {
        createEnumClasses()
        filterModels()
        val p = proteinApiConfiguration.packageName
        apiInterfaceTypeSpec = createApiRetrofitInterface(createApiResponseBodyModel())
        createDatabaseEntities(p)
        createDao(p)
        createDatabase(p)
        createMappers(p)
        createMapHelper(p)
        createPutMapHelper(p)
    }

    fun getGeneratedTypeSpec(): TypeSpec {
        return apiInterfaceTypeSpec
    }

    fun generateFiles(
        isSyncDto: Boolean,
        isDatabaseEntities: Boolean,
        isDao: Boolean,
        isDatabase: Boolean,
        isMappers: Boolean,
        isHelper: Boolean,
        isPutHelper: Boolean
    ) {
        /*StorageUtils.generateFiles(
          proteinApiConfiguration.moduleName, proteinApiConfiguration.packageName, apiInterfaceTypeSpec)*/
        if (isSyncDto) {
            for (typeSpec in responseBodyModelListTypeSpec) {
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
        if (isDao) {
            for (typeSpec in daoTypeSpec) {
                StorageUtils.generateFiles(
                    proteinApiConfiguration.moduleName, proteinApiConfiguration.packageName + ".database.dao", typeSpec)
            }
        }
        if (isDatabase) {
            StorageUtils.generateFiles(
                proteinApiConfiguration.moduleName, proteinApiConfiguration.packageName + ".database", databaseTypeSpec)
        }
        if (isMappers) {
            for (typeSpec in mappersTypeSpec) {
                StorageUtils.generateFiles(
                    proteinApiConfiguration.moduleName, proteinApiConfiguration.packageName + ".mapper", typeSpec)
            }
        }
        if (isHelper) {
            StorageUtils.generateFiles(
                proteinApiConfiguration.moduleName, proteinApiConfiguration.packageName + ".helper", mapeHelperTypeSpec)
        }
        if (isPutHelper) {
            StorageUtils.generateFiles(
                proteinApiConfiguration.moduleName, proteinApiConfiguration.packageName + ".helper", mapPutHelperTypeSpec)
        }
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
        if (swaggerModel.definitions != null && !swaggerModel.definitions.isEmpty()) {
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
        }
    }

    private fun addOperationResponseEnums() {
        if (swaggerModel.paths != null && !swaggerModel.paths.isEmpty()) {
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
        }
    }

    private fun filterModels() {
        if (swaggerModel.definitions != null && !swaggerModel.definitions.isEmpty()) {
            val types = arrayListOf<String>()
            types.add("SyncData")
            var i = 0
            while (i < types.size) {
                val name = types[i]
                val definition = findDefinition(name)
                models[name.replace(Regex("SyncDto\\b|Dto\\b"), "")] = definition
                findRefs(definition, types)
                i++
            }
        }
    }

    private fun createApiResponseBodyModel(): List<String> {
        val classNameList = ArrayList<String>()

        for (definition in models) {
            val modelClassTypeSpec: TypeSpec.Builder = TypeSpec.classBuilder(definition.key).addModifiers(KModifier.DATA)
            classNameList.add(definition.key)

            if (definition.value.properties != null) {
                val primaryConstructor = FunSpec.constructorBuilder()
                for (modelProperty in definition.value.properties) {
                    val typeName: TypeName = getTypeName(modelProperty)
                    val propertySpec = PropertySpec.builder(modelProperty.key, typeName)
                        .addAnnotation(AnnotationSpec.builder(SerializedName::class)
                            .addMember("\"${modelProperty.key}\"")
                            .build())
                        .initializer(modelProperty.key)
                        .build()
                    val parameter = ParameterSpec.builder(modelProperty.key, typeName)
                        .defaultValue("null")
                        .build()
                    primaryConstructor.addParameter(parameter)
                    modelClassTypeSpec.addProperty(propertySpec)
                }
                modelClassTypeSpec.primaryConstructor(primaryConstructor.build())

                responseBodyModelListTypeSpec.add(modelClassTypeSpec.build())
            }
        }

        return classNameList
    }

    private fun createDatabaseEntities(packageName: String): List<String> {
        val classNameList = ArrayList<String>()

        for (definition in models) {
            val modelClassTypeSpec: TypeSpec.Builder = TypeSpec.classBuilder(definition.key + "Entity").addModifiers(KModifier.DATA)
            classNameList.add(definition.key + "Entity")

            val foreignKeys = mutableSetOf<String>()

            if (definition.value.properties != null) {
                val primaryConstructor = FunSpec.constructorBuilder()
                for (modelProperty in definition.value.properties) {
                    if (modelProperty.value.type == ARRAY_SWAGGER_TYPE) {
                        continue
                    }
                    var propertyName = modelProperty.key
                    val typeName = if (modelProperty.value.type == REF_SWAGGER_TYPE) {
                        propertyName = "id${propertyName.capitalize()}"
                        Int::class.asTypeName().requiredOrNullable(modelProperty.value.required)
                    } else {
                        getTypeName(modelProperty)
                    }
                    val propertySpecBuilder = if (modelProperty.key == "id") {
                        propertyName += definition.key
                        PropertySpec.builder(propertyName , typeName)
                            .addAnnotation(AnnotationSpec.builder(PrimaryKey::class)
                                .addMember("autoGenerate = true")
                                .build())
                    } else {
                        if (propertyName.startsWith("id")) {
                            foreignKeys.add(propertyName)
                        }
                        PropertySpec.builder(propertyName, typeName)
                    }

                    val propertySpec = propertySpecBuilder
                        .initializer(propertyName)
                        .addAnnotation(AnnotationSpec.builder(ColumnInfo::class)
                            .addMember("%L = %S", "name", propertyName.snake())
                            .build())
                        .mutable()
                        .build()
                    val parameter = ParameterSpec.builder(propertyName, typeName)
                        .defaultValue("null")
                        .build()
                    primaryConstructor.addParameter(parameter)
                    modelClassTypeSpec.addProperty(propertySpec)
                }
                modelClassTypeSpec.primaryConstructor(primaryConstructor.build())

                val entityAn = AnnotationSpec.builder(Entity::class)
                    .addMember("%L = %S", "tableName", definition.key.snake())
                val annot = foreignKeys.map {
                    val parent = if (it.contains("ContactPersonGroup")
                    || it.contains("PolicemanGroup")
                    || it.contains("GuarantorGroup")
                    || it.contains("ClerkGroup")) {
                        "idPersonGroup"
                    } else {
                        it
                    }
                    val entityClass = ClassName("$packageName.database.entity", parent.substring(2) + "Entity")
                    AnnotationSpec.builder(ForeignKey::class)
                        .addMember("%L = %T::class", "entity", entityClass)
                        .addMember("\n%L = arrayOf(%S)", "parentColumns", parent.snake())
                        .addMember("\n%L = arrayOf(%S)", "childColumns", it.snake())
                        .addMember("\n%L = %T.CASCADE", "onDelete", ForeignKey::class)
                        .addMember("\n%L = %T.CASCADE", "onUpdate", ForeignKey::class)
                        .build()
                }
                addEmbeddedAnnotations(entityAn, annot)

                modelClassTypeSpec.addAnnotation(entityAn.build())

                databaseEntitiesTypeSpec.add(modelClassTypeSpec.build())
            }
        }

        return classNameList
    }

    private fun createDao(packageName: String) {
        for (definition in models) {
            val baseDao = ClassName("de.weinandit.bestatterprogramma.modules.database.dao", "BaseDao")
            val entityName = definition.key + "Entity"
            val snakeCaseName = definition.key.snake()
            val parameterType = ClassName("$packageName.database.entity", entityName)
            val modelClassTypeSpec = TypeSpec.interfaceBuilder(definition.key + "Dao")
                .addSuperinterface(baseDao.parameterizedBy(parameterType))
                .addAnnotation(AnnotationSpec.builder(Dao::class)
                    .build())
            if (definition.value.properties != null) {

                modelClassTypeSpec.addFunction(FunSpec.builder("findAll")
                    .addModifiers(KModifier.ABSTRACT)
                    .returns(List::class.asTypeName().parameterizedBy(parameterType))
                    .addAnnotation(AnnotationSpec.builder(Query::class)
                        .addMember("%S", "SELECT * FROM `$snakeCaseName`")
                        .build())
                    .build())

                modelClassTypeSpec.addFunction(FunSpec.builder("findAllFlow")
                    .addModifiers(KModifier.ABSTRACT)
                    .returns(Flowable::class.asTypeName().parameterizedBy(List::class.asTypeName().parameterizedBy(parameterType)))
                    .addAnnotation(AnnotationSpec.builder(Query::class)
                        .addMember("%S", "SELECT * FROM `$snakeCaseName`")
                        .build())
                    .build())

                modelClassTypeSpec.addFunction(FunSpec.builder("findById")
                    .addModifiers(KModifier.ABSTRACT)
                    .returns(parameterType.asNullable())
                    .addAnnotation(AnnotationSpec.builder(Query::class)
                        .addMember("%S", "SELECT * FROM `$snakeCaseName` WHERE id_$snakeCaseName = :id")
                        .build())
                    .addParameter("id", Int::class)
                    .build())

                if (definition.value.properties.containsKey("modified")) {
                    modelClassTypeSpec.addFunction(FunSpec.builder("findSinceBefore")
                        .addModifiers(KModifier.ABSTRACT)
                        .returns(List::class.asTypeName().parameterizedBy(parameterType))
                        .addAnnotation(AnnotationSpec.builder(Query::class)
                            .addMember("%S", "SELECT * FROM `$snakeCaseName` WHERE modified BETWEEN :since AND :before")
                            .build())
                        .addParameter("since", Date::class)
                        .addParameter("before", Date::class)
                        .build())
                }

                daoTypeSpec.add(modelClassTypeSpec.build())
            }
        }
    }

    private fun createDatabase(packageName: String) {
        val sorted = daoTypeSpec.sortedWith(compareBy { it.name })
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

        sorted.forEach {
            val name = it.name?: "none"
            val type = ClassName("$packageName.database.dao", name)
            modelClassTypeSpec.addFunction(FunSpec.builder(name.decapitalize())
                .returns(type)
                .addModifiers(KModifier.ABSTRACT)
                .build())
        }

        val depends = FunSpec.builder("depend")
        depends.addCode("/*\n")
        sorted.forEach {
            val name = it.name?: "none"
            depends.addStatement("single { get<BMSDatabase>().%L() }", name.decapitalize())
        }
        depends.addCode("\n*/")
        modelClassTypeSpec.addFunction(depends.build())
        databaseTypeSpec = modelClassTypeSpec.build()
    }

    private fun createMappers(packageName: String) {
        val classNameList = ArrayList<String>()

        for (definition in models) {
            val modelClassTypeSpec: TypeSpec.Builder = TypeSpec.classBuilder(definition.key + "EntityMapper")
            classNameList.add(definition.key + "Entity")

            if (definition.value.properties != null) {
                val syncEntity = ClassName("$packageName.sync.entity", definition.key)
                val databaseEntity = ClassName("$packageName.database.entity", definition.key + "Entity")

                val parameters = definition.value.properties.filter {
                    it.value.type != ARRAY_SWAGGER_TYPE
                }
                val statement = parameters.entries.joinToString(separator = ",\n    ", prefix = "(\n    ", postfix = "\n)") {
                    var param = it.key
                    if (it.value.type == REF_SWAGGER_TYPE) {
                        param = "id${param.capitalize()}"
                        return@joinToString "$param = entity.${it.key}?.id"
                    }
                    if (param == "id") {
                        param += definition.key
                    }
                    return@joinToString "$param = entity.${it.key}"
                }
                val statementFrom = parameters.entries.joinToString(separator = ",\n    ", prefix = "(\n    ", postfix = "\n)") {
                    var param = it.key
                    if (it.value.type == REF_SWAGGER_TYPE) {
                        return@joinToString ""
                    }
                    if (param == "id") {
                        param += definition.key
                    }
                    return@joinToString "${it.key} = entity.$param"
                }

                val funcFromSyncToDatabase = FunSpec.builder("transform")
                    .addParameter("entity", syncEntity)
                    .addStatement("return %T%L", databaseEntity, statement)
                    .build()
                val funcFromDatabaseToSync = FunSpec.builder("transform")
                    .addParameter("entity", databaseEntity)
                    .addStatement("return %T%L", syncEntity, statementFrom)
                    .build()

                modelClassTypeSpec.addFunction(funcFromSyncToDatabase)
                modelClassTypeSpec.addFunction(funcFromDatabaseToSync)
                mappersTypeSpec.add(modelClassTypeSpec.build())
            }
        }

        //return classNameList
    }

    private fun createMapHelper(packageName: String) {
        val syncParams = ClassName("", "GetContent.SyncParams")

        val ttt = Map::class.asTypeName()
            .parameterizedBy(Int::class.asTypeName(), syncParams)

        val modelClassTypeSpec = TypeSpec.classBuilder("MapHelper")
            .primaryConstructor(FunSpec.constructorBuilder()
                .addParameter("st", Map::class.asTypeName()
                    .parameterizedBy(String::class.asTypeName(), ttt))
                .build())

        val map = mutableSetOf<String>()

        models.forEach { entry ->
            val name = entry.key
            val entity = ClassName("$packageName.sync.entity", name)
            val mapper = ClassName("$packageName.mapper", name + "EntityMapper")
            val returnEntity = ClassName("$packageName.database.entity", name + "Entity")

            val f = FunSpec.builder("map")
                .addParameter(name.decapitalize(), entity)
                .addParameter("mapper", mapper)
                .returns(returnEntity)
                .addStatement("val entity = mapper.transform(%L)", name.decapitalize())
            entry.value.properties.forEach {
                if (it.key.startsWith("id")) {
                    val fieldName = if (it.key == "id") {
                        it.key + name
                    } else {
                        it.key
                    }
                    val mapName = fieldName.substring(2).decapitalize() + "Id"
                    map.add(mapName)
                    f.addStatement("entity.%L = %L[entity.%L]?.localId", fieldName, mapName, fieldName)
                }
            }

            f.addStatement("return entity")

            modelClassTypeSpec.addFunction(f.build())
        }

        map.forEach {
            val name = it.substring(0, it.length - 2).snake()
            modelClassTypeSpec.addProperty(PropertySpec.builder(it, ttt)
                .initializer("st[%S]?: mapOf()", name)
                .build())
        }

        mapeHelperTypeSpec = modelClassTypeSpec.build()
    }

    private fun createPutMapHelper(packageName: String) {
        val ttt = ClassName("", "MutableSet")
            .parameterizedBy(Int::class.asTypeName().asNullable())

        val modelClassTypeSpec = TypeSpec.classBuilder("PutMapHelper")

        val map = mutableSetOf<String>()

        models.forEach { entry ->
            val name = entry.key
            val syncEntity = ClassName("$packageName.sync.entity", name)
            val mapper = ClassName("$packageName.mapper", name + "EntityMapper")
            val databaseEntity = ClassName("$packageName.database.entity", name + "Entity")

            val f = FunSpec.builder("map")
                .addParameter(name.decapitalize(), databaseEntity)
                .addParameter("mapper", mapper)
                .returns(syncEntity)
            entry.value.properties.forEach {
                if (it.key.startsWith("id")) {
                    val databaseFieldName = if (it.key == "id") {
                        it.key + name
                    } else {
                        it.key
                    }
                    val mapName = databaseFieldName.substring(2).decapitalize() + "Id"
                    map.add(mapName)
                    f.addStatement("%L.add(%L.%L)", mapName, name.decapitalize(), databaseFieldName)
                }
            }

            f.addStatement("return mapper.transform(%L)", name.decapitalize())

            modelClassTypeSpec.addFunction(f.build())
        }

        map.forEach {
            modelClassTypeSpec.addProperty(PropertySpec.builder(it, ttt)
                .initializer("mutableSetOf()")
                .build())
        }

        mapPutHelperTypeSpec = modelClassTypeSpec.build()
    }

    private fun addEmbeddedAnnotations(entityAn: AnnotationSpec.Builder, annot: List<AnnotationSpec>) {
        when (annot.size) {
            0 -> return
            1 -> entityAn.addMember("%L = [\n%L\n]", "foreignKeys", annot[0])
            2 -> entityAn.addMember("%L = [\n%L,\n%L\n]", "foreignKeys", annot[0], annot[1])
            3 -> entityAn.addMember("%L = [\n%L,\n%L,\n%L\n]", "foreignKeys", annot[0], annot[1], annot[2])
            4 -> entityAn.addMember("%L = [\n%L,\n%L,\n%L,\n%L\n]",
                "foreignKeys", annot[0], annot[1], annot[2], annot[3])
            5 -> entityAn.addMember("%L = [\n%L,\n%L,\n%L,\n%L,\n%L\n]",
                "foreignKeys", annot[0], annot[1], annot[2], annot[3], annot[4])
            6 -> entityAn.addMember("%L = [\n%L,\n%L,\n%L,\n%L,\n%L,\n%L\n]",
                "foreignKeys", annot[0], annot[1], annot[2], annot[3], annot[4], annot[5])
            7 -> entityAn.addMember("%L = [\n%L,\n%L,\n%L,\n%L,\n%L,\n%L,\n%L\n]",
                "foreignKeys", annot[0], annot[1], annot[2], annot[3], annot[4], annot[5], annot[6])
            8 -> entityAn.addMember("%L = [\n%L,\n%L,\n%L,\n%L,\n%L,\n%L,\n%L,\n%L\n]",
                "foreignKeys", annot[0], annot[1], annot[2], annot[3], annot[4], annot[5], annot[6], annot[7])
            9 -> entityAn.addMember("%L = [\n%L,\n%L,\n%L,\n%L,\n%L,\n%L,\n%L,\n%L,\n%L\n]",
                "foreignKeys", annot[0], annot[1], annot[2], annot[3], annot[4], annot[5], annot[6], annot[7], annot[8])
            10 -> entityAn.addMember("%L = [\n%L,\n%L,\n%L,\n%L,\n%L,\n%L,\n%L,\n%L,\n%L,\n%L\n]",
                "foreignKeys", annot[0], annot[1], annot[2], annot[3], annot[4], annot[5], annot[6], annot[7], annot[8],
                annot[9])
            11 -> entityAn.addMember("%L = [\n%L,\n%L,\n%L,\n%L,\n%L,\n%L,\n%L,\n%L,\n%L,\n%L,\n%L\n]",
                "foreignKeys", annot[0], annot[1], annot[2], annot[3], annot[4], annot[5], annot[6], annot[7], annot[8],
                annot[9], annot[10])
        }
    }

    private fun findDefinition(name: String): Model {
        return swaggerModel.definitions[name] ?: throw IllegalArgumentException()
    }

    private fun findRefs(model: Model, types: ArrayList<String>) {
        model.properties.forEach { _, property ->
            if (property.type == REF_SWAGGER_TYPE) {
                val ref = (property as RefProperty).simpleRef
                if (!types.contains(ref)) types.add(ref)
            }
            if (property.type == ARRAY_SWAGGER_TYPE) {
                val arrayProperty = property as ArrayProperty
                val items = arrayProperty.items
                if (items is RefProperty) {
                    val ref = items.simpleRef
                    if (!types.contains(ref)) types.add(ref)
                }
            }
        }
    }

    private fun createApiRetrofitInterface(classNameList: List<String>): TypeSpec {
        val apiInterfaceTypeSpecBuilder = TypeSpec
            .interfaceBuilder("${proteinApiConfiguration.componentName}ApiInterface")
            .addModifiers(KModifier.PUBLIC)

        addApiPathMethods(apiInterfaceTypeSpecBuilder, classNameList)

        return apiInterfaceTypeSpecBuilder.build()
    }

    private fun addApiPathMethods(apiInterfaceTypeSpec: TypeSpec.Builder, classNameList: List<String>) {
        if (swaggerModel.paths != null && !swaggerModel.paths.isEmpty()) {
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
        }
    }

    private fun getMethodParametersDocs(operation: MutableMap.MutableEntry<HttpMethod, Operation>): Iterable<String> {
        return operation.value.parameters.filterNot { it.description.isNullOrBlank() }.map { "@param ${it.name} ${it.description}" }
    }

    private fun getTypeName(modelProperty: MutableMap.MutableEntry<String, Property>): TypeName {
        val property = modelProperty.value
        return when {
            property.type == REF_SWAGGER_TYPE ->
                TypeVariableName.invoke((property as RefProperty).simpleRef).requiredOrNullable(property.required)

            property.type == ARRAY_SWAGGER_TYPE -> {
                val arrayProperty = property as ArrayProperty
                getTypedArray(arrayProperty.items).requiredOrNullable(arrayProperty.required)
            }
            else -> getKotlinClassTypeName(property.type, property.format).requiredOrNullable(property.required)
        }
    }

    private fun getMethodParameters(
        operation: MutableMap.MutableEntry<HttpMethod, Operation>
    ): Iterable<ParameterSpec> {
        return operation.value.parameters.mapNotNull { parameter ->
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
        }
    }

    private fun getBodyParameterSpec(parameter: Parameter): TypeName {
        val bodyParameter = parameter as BodyParameter
        val schema = bodyParameter.schema

        return when (schema) {
            is RefModel -> ClassName.bestGuess(schema.simpleRef.capitalize()).requiredOrNullable(parameter.required)

            is ArrayModel -> getTypedArray(schema.items).requiredOrNullable(parameter.required)

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

    private fun getTypedArray(items: Property): TypeName {
        val typeProperty = when (items) {
            is LongProperty -> TypeVariableName.invoke(Long::class.simpleName!!)
            is IntegerProperty -> TypeVariableName.invoke(Int::class.simpleName!!)
            is FloatProperty -> TypeVariableName.invoke(Float::class.simpleName!!)
            is DoubleProperty -> TypeVariableName.invoke(Float::class.simpleName!!)
            is RefProperty -> TypeVariableName.invoke(items.simpleRef)
            else -> getKotlinClassTypeName(items.type, items.format)
        }
        return List::class.asClassName().parameterizedBy(typeProperty)
    }

    private fun TypeName.requiredOrNullable(required: Boolean) = if (required) this else asNullable()

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

    private fun getKotlinClassTypeName(type: String, format: String? = null): TypeName {
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
            else -> TypeVariableName.invoke(type.capitalize())
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
        return StorageUtils.generateString(proteinApiConfiguration.packageName, apiInterfaceTypeSpec)
    }

    fun getGeneratedModelsString(): String {
        var generated = ""
        for (typeSpec in responseBodyModelListTypeSpec) {
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
}
