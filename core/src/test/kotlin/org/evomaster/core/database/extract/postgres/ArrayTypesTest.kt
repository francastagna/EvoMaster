package org.evomaster.core.database.extract.postgres

import org.evomaster.client.java.controller.api.dto.database.schema.DatabaseType
import org.evomaster.client.java.controller.db.SqlScriptRunner
import org.evomaster.client.java.controller.internal.db.SchemaExtractor
import org.evomaster.core.database.DbActionTransformer
import org.evomaster.core.database.SqlInsertBuilder
import org.evomaster.core.search.gene.*
import org.evomaster.core.search.gene.sql.SqlMultidimensionalArrayGene
import org.evomaster.core.search.gene.sql.SqlNullableGene
import org.evomaster.core.search.service.Randomness
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Created by jgaleotti on 07-May-19.
 */
class ArrayTypesTest : ExtractTestBasePostgres() {

    override fun getSchemaLocation() = "/sql_schema/postgres_array_types.sql"

    private val rand = Randomness()

    @BeforeEach
    fun initRand() {
        rand.updateSeed(42)
    }


    @Test
    fun testExtractionOfArrayTypes() {

        val schema = SchemaExtractor.extract(connection)

        assertNotNull(schema)

        assertEquals("public", schema.name.lowercase())
        assertEquals(DatabaseType.POSTGRES, schema.databaseType)

        assertTrue(schema.tables.any { it.name.equals("ArrayTypes".lowercase()) })
        val table = schema.tables.find { it.name.equals("ArrayTypes".lowercase()) }

        assertTrue(table!!.columns.any { it.name.equals("nonArrayColumn".lowercase()) })
        val nonArrayColumnDto = table.columns.find { it.name.equals("nonArrayColumn".lowercase()) }!!
        assertEquals("int4", nonArrayColumnDto.type)
        assertEquals(0, nonArrayColumnDto.numberOfDimensions)


        assertTrue(table.columns.any { it.name.equals("arrayColumn".lowercase()) })
        val arrayColumnDto = table.columns.find { it.name.equals("arrayColumn".lowercase()) }!!
        assertEquals("_int4", arrayColumnDto.type)
        assertEquals(1, arrayColumnDto.numberOfDimensions)

        assertTrue(table.columns.any { it.name.equals("matrixColumn".lowercase()) })
        val matrixColumnDto = table.columns.find { it.name.equals("matrixColumn".lowercase()) }!!
        assertEquals("_int4", matrixColumnDto.type)
        assertEquals(2, matrixColumnDto.numberOfDimensions)

        assertTrue(table.columns.any { it.name.equals("spaceColumn".lowercase()) })
        val spaceColumnDto = table.columns.find { it.name.equals("spaceColumn".lowercase()) }!!
        assertEquals("_int4", spaceColumnDto.type)
        assertEquals(3, spaceColumnDto.numberOfDimensions)

        assertTrue(table.columns.any { it.name.equals("manyDimensionsColumn".lowercase()) })
        val manyDimensionsColumnDto = table.columns.find { it.name.equals("manyDimensionsColumn".lowercase()) }!!
        assertEquals("_int4", manyDimensionsColumnDto.type)
        assertEquals(4, manyDimensionsColumnDto.numberOfDimensions)

        assertTrue(table.columns.any { it.name.equals("exactSizeArrayColumn".lowercase()) })
        val exactSizeArrayColumnDto = table.columns.find { it.name.equals("exactSizeArrayColumn".lowercase()) }!!
        assertEquals("_int4", exactSizeArrayColumnDto.type)
        assertEquals(1, exactSizeArrayColumnDto.numberOfDimensions)

        assertTrue(table.columns.any { it.name.equals("exactSizeMatrixColumn".lowercase()) })
        val exactSizeMatrixColumnDto = table.columns.find { it.name.equals("exactSizeMatrixColumn".lowercase()) }!!
        assertEquals("_int4", exactSizeMatrixColumnDto.type)
        assertEquals(2, exactSizeMatrixColumnDto.numberOfDimensions)
    }

    @Test
    fun testBuildGenesOfArrayTypes() {

        val schema = SchemaExtractor.extract(connection)


        val builder = SqlInsertBuilder(schema)
        val actions = builder.createSqlInsertionAction(
            "ArrayTypes",
            setOf(
                "nonArrayColumn",
                "arrayColumn",
                "matrixColumn",
                "spaceColumn",
                "manyDimensionsColumn",
                "exactSizeArrayColumn",
                "exactSizeMatrixColumn"
            )
        )

        val genes = actions[0].seeGenes()

        assertEquals(7, genes.size)
        assertTrue(genes[0] is IntegerGene)
        assertTrue(genes[1] is SqlMultidimensionalArrayGene<*>)
        assertTrue(genes[2] is SqlMultidimensionalArrayGene<*>)
        assertTrue(genes[3] is SqlMultidimensionalArrayGene<*>)
        assertTrue(genes[4] is SqlMultidimensionalArrayGene<*>)
        assertTrue(genes[5] is SqlMultidimensionalArrayGene<*>)
        assertTrue(genes[6] is SqlMultidimensionalArrayGene<*>)

        val arrayColumn = genes[1] as SqlMultidimensionalArrayGene<IntegerGene>
        assertEquals(1, arrayColumn.numberOfDimensions)
        val matrixColumn = genes[2] as SqlMultidimensionalArrayGene<IntegerGene>
        assertEquals(2, matrixColumn.numberOfDimensions)
        val spaceColumn = genes[3] as SqlMultidimensionalArrayGene<IntegerGene>
        assertEquals(3, spaceColumn.numberOfDimensions)

        arrayColumn.doInitialize(rand)
        do {
            arrayColumn.randomize(rand,tryToForceNewValue = false)
        } while (arrayColumn.getDimensionSize( 0 )!=3)
        assertEquals(3, arrayColumn.getDimensionSize(0))

        arrayColumn.getElement(listOf(0)).value = 1
        arrayColumn.getElement(listOf(1)).value = 2
        arrayColumn.getElement(listOf(2)).value = 3

        assertEquals("\"{1,2,3}\"", arrayColumn.getValueAsPrintableString())

    }


    @Test
    fun testInsertValuesOfArrayGenes() {

        val schema = SchemaExtractor.extract(connection)

        val builder = SqlInsertBuilder(schema)
        val actions = builder.createSqlInsertionAction(
            "ArrayTypes",
            setOf(
                "nonArrayColumn",
                "arrayColumn",
                "matrixColumn",
                "spaceColumn",
                "manyDimensionsColumn",
                "exactSizeArrayColumn",
                "exactSizeMatrixColumn"
            )
        )
        actions.forEach { it.doInitialize(rand) }
        val dbCommandDto = DbActionTransformer.transform(actions)
        SqlScriptRunner.execInsert(connection, dbCommandDto.insertions)

    }

    @Test
    fun testInsertNullIntoNullableArray() {

        val schema = SchemaExtractor.extract(connection)

        val builder = SqlInsertBuilder(schema)
        val actions = builder.createSqlInsertionAction(
            "NullableArrayTable",
            setOf(
                "nullableArrayColumn"
            )
        )
        val genes = actions[0].seeGenes()

        assertEquals(1, genes.size)

        val nullableArrayColumn = genes[0] as SqlMultidimensionalArrayGene<*>
        assertEquals(1, nullableArrayColumn.numberOfDimensions)
        assertTrue(nullableArrayColumn.template is SqlNullableGene)

        nullableArrayColumn.doInitialize(rand)
        do {
            nullableArrayColumn.randomize(rand,tryToForceNewValue = false)
        } while (nullableArrayColumn.getDimensionSize(0)!=1)

        val sqlNullableGene = nullableArrayColumn.getElement(listOf(0)) as SqlNullableGene
        sqlNullableGene.isPresent = false

        assertEquals("\"{NULL}\"", nullableArrayColumn.getValueAsPrintableString())


        val dbCommandDto = DbActionTransformer.transform(actions)
        SqlScriptRunner.execInsert(connection, dbCommandDto.insertions)

    }

    @Test
    fun testInsertStringIntoArray() {

        val schema = SchemaExtractor.extract(connection)

        val builder = SqlInsertBuilder(schema)
        val actions = builder.createSqlInsertionAction(
            "StringArrayTable",
            setOf(
                "stringArrayColumn"
            )
        )
        val genes = actions[0].seeGenes()

        assertEquals(1, genes.size)

        val stringArrayColumn = genes[0] as SqlMultidimensionalArrayGene<*>
        assertEquals(1, stringArrayColumn.numberOfDimensions)
        assertTrue(stringArrayColumn.template is StringGene)


        stringArrayColumn.doInitialize(rand)
        do {
            stringArrayColumn.randomize(rand,tryToForceNewValue = false)
        } while (stringArrayColumn.getDimensionSize(0)!=1)

        val stringGene = stringArrayColumn.getElement(listOf(0)) as StringGene
        stringGene.value = "Hello World"

        assertEquals("\"{\"Hello World\"}\"", stringArrayColumn.getValueAsPrintableString())

        val dbCommandDto = DbActionTransformer.transform(actions)
        SqlScriptRunner.execInsert(connection, dbCommandDto.insertions)

    }

    @Test
    fun testInsertStringIntoArrayWithQuotes() {

        val schema = SchemaExtractor.extract(connection)

        val builder = SqlInsertBuilder(schema)
        val actions = builder.createSqlInsertionAction(
                "StringArrayTable",
                setOf(
                        "stringArrayColumn"
                )
        )
        val genes = actions[0].seeGenes()

        assertEquals(1, genes.size)

        val stringArrayColumn = genes[0] as SqlMultidimensionalArrayGene<*>
        assertEquals(1, stringArrayColumn.numberOfDimensions)
        assertTrue(stringArrayColumn.template is StringGene)
        stringArrayColumn.doInitialize(rand)
        do {
            stringArrayColumn.randomize(rand,tryToForceNewValue = false)
        } while (stringArrayColumn.getDimensionSize(0)!=1)

        val stringGene = stringArrayColumn.getElement(listOf(0)) as StringGene
        stringGene.value = "Hello\"World"

        assertEquals("\"{\"Hello\\\"World\"}\"", stringArrayColumn.getValueAsPrintableString())

        val dbCommandDto = DbActionTransformer.transform(actions)
        SqlScriptRunner.execInsert(connection, dbCommandDto.insertions)

    }


    @Test
    fun testInsertStringIntoArrayWithApostrophe() {

        val schema = SchemaExtractor.extract(connection)

        val builder = SqlInsertBuilder(schema)
        val actions = builder.createSqlInsertionAction(
                "StringArrayTable",
                setOf(
                        "stringArrayColumn"
                )
        )
        val genes = actions[0].seeGenes()

        assertEquals(1, genes.size)

        val stringArrayColumn = genes[0] as SqlMultidimensionalArrayGene<*>
        assertEquals(1, stringArrayColumn.numberOfDimensions)
        assertTrue(stringArrayColumn.template is StringGene)

        stringArrayColumn.doInitialize(rand)
        do {
            stringArrayColumn.randomize(rand,tryToForceNewValue = false)
        } while (stringArrayColumn.getDimensionSize(0)!=1)

        val stringGene = stringArrayColumn.getElement(listOf(0)) as StringGene
        stringGene.value = "Hello'World"

        assertEquals("\"{\"Hello'World\"}\"", stringArrayColumn.getValueAsPrintableString())

        val dbCommandDto = DbActionTransformer.transform(actions)
        SqlScriptRunner.execInsert(connection, dbCommandDto.insertions)

    }

}