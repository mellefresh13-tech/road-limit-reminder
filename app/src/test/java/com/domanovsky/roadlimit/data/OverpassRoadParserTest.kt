package com.domanovsky.roadlimit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverpassRoadParserTest {
    @Test
    fun parsesRoadTagsAndGeometry() {
        val json = """{"elements":[{"type":"way","id":123,"tags":{"highway":"primary","maxspeed":"60","name":"Test Street"},"geometry":[{"lat":53.9,"lon":27.55},{"lat":53.91,"lon":27.56}]}]}"""
        val roads = OverpassRoadParser.parse(json)
        assertEquals(1, roads.size)
        assertEquals(123L, roads.first().id)
        assertEquals("Test Street", roads.first().name)
        assertEquals("60", roads.first().tags["maxspeed"])
        assertEquals(2, roads.first().geometry.size)
    }

    @Test
    fun parsesPhysicalTrafficSignNodesAndDirectionTags() {
        val json = """{"elements":[
          {"type":"node","id":77,"lat":53.9,"lon":27.55,"tags":{"traffic_sign":"BY:3.24","maxspeed":"40","direction":"forward"}},
          {"type":"node","id":78,"lat":53.9001,"lon":27.5501,"tags":{"traffic_sign:forward":"BY:3.24","maxspeed":"40"}},
          {"type":"way","id":123,"tags":{"highway":"primary","traffic_sign:forward":"BY:3.24","maxspeed:forward":"40"},"geometry":[{"lat":53.9,"lon":27.55},{"lat":53.91,"lon":27.56}]}
        ]}"""
        val data = OverpassRoadParser.parseData(json)
        assertEquals(2, data.signs.size)
        assertEquals(77L, data.signs.first().id)
        assertEquals("40", data.signs.first().tags["maxspeed"])
        assertEquals("40", data.roads.first().tags["maxspeed:forward"])
    }

    @Test
    fun parsesBelarusCountryAndSettlementAreas() {
        val json = """{"elements":[
          {"type":"area","id":1,"tags":{"boundary":"administrative","admin_level":"2","ISO3166-1":"BY"}},
          {"type":"area","id":2,"tags":{"place":"city","name":"Minsk"}},
          {"type":"way","id":123,"tags":{"highway":"primary"},"geometry":[{"lat":53.9,"lon":27.55},{"lat":53.91,"lon":27.56}]}
        ]}"""
        val data = OverpassRoadParser.parseData(json)
        assertEquals("BY", data.countryCode)
        assertEquals(true, data.insideSettlement)
        assertEquals(1, data.roads.size)
    }

    @Test
    fun identifiesOutsideSettlementOnlyWhenCountryAreaWasReturned() {
        val data = OverpassRoadParser.parseData("""{"elements":[{"type":"area","id":1,"tags":{"boundary":"administrative","admin_level":"2","ISO3166-1":"BY"}}]}""")
        assertEquals("BY", data.countryCode)
        assertEquals(false, data.insideSettlement)
    }

    @Test
    fun skipsPrivateRoadsAndIncompleteGeometry() {
        val json = """{"elements":[
          {"type":"way","id":1,"tags":{"highway":"service","access":"private"},"geometry":[{"lat":1,"lon":1},{"lat":2,"lon":2}]},
          {"type":"way","id":2,"tags":{"highway":"residential"},"geometry":[{"lat":1,"lon":1}]}
        ]}"""
        assertTrue(OverpassRoadParser.parse(json).isEmpty())
    }
}
