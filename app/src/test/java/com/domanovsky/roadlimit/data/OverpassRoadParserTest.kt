package com.domanovsky.roadlimit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverpassRoadParserTest {
    @Test
    fun parsesRoadTagsAndGeometry() {
        val json = """
            {
              "elements": [{
                "type": "way",
                "id": 123,
                "tags": {"highway": "primary", "maxspeed": "60", "name": "Test Street"},
                "geometry": [
                  {"lat": 53.9, "lon": 27.55},
                  {"lat": 53.91, "lon": 27.56}
                ]
              }]
            }
        """.trimIndent()

        val roads = OverpassRoadParser.parse(json)
        assertEquals(1, roads.size)
        assertEquals(123L, roads.first().id)
        assertEquals("Test Street", roads.first().name)
        assertEquals("60", roads.first().tags["maxspeed"])
        assertEquals(2, roads.first().geometry.size)
    }

    @Test
    fun skipsPrivateRoadsAndIncompleteGeometry() {
        val json = """
            {
              "elements": [
                {"type":"way","id":1,"tags":{"highway":"service","access":"private"},"geometry":[{"lat":1,"lon":1},{"lat":2,"lon":2}]},
                {"type":"way","id":2,"tags":{"highway":"residential"},"geometry":[{"lat":1,"lon":1}]}
              ]
            }
        """.trimIndent()
        assertTrue(OverpassRoadParser.parse(json).isEmpty())
    }
}
