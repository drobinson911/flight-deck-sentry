package com.uasflightdeck.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {
    private val now = 1_790_189_600_000L

    @Test fun overwatchStationReadsb() {
        // shape of Overwatch http_api /data/aircraft.json (tracks.py to_readsb)
        val j = """{"now":1790189600.0,"messages":10,
          "aircraft":[
            {"hex":"A479EF","type":"adsb_icao","flight":"N388KM ","r":null,"t":null,"lat":39.40,"lon":-120.05,
             "alt_baro":7460,"alt_geom":null,"gs":160.0,"track":33.0,"baro_rate":-300,"geom_rate":null,"seen":0.4,"seen_pos":0.4,"src":"adsb","origin":"cube"},
            {"hex":"f00001","type":"adsb_other","flight":null,"lat":39.41,"lon":-120.04,"alt_baro":8000,"seen":0.1,"seen_pos":0.1,"src":"rid"},
            {"hex":"a00002","flight":"N2GND","lat":39.3,"lon":-120.1,"alt_baro":"ground","gs":3.0,"seen":1.0,"seen_pos":1.0,"src":"adsb"},
            {"hex":"a00003","flight":"NOPOS","alt_baro":5000,"seen":1.0}
          ],
          "ac":[{"hex":"a479ef","flight":"N388KM","lat":39.40,"lon":-120.05,"alt_baro":7460,"seen_pos":2.0,"src":"adsb"}]}"""
        val t = Parsers.parseReadsb(j, now, "station").associateBy { it.hex }
        assertEquals(setOf("a479ef", "a00002"), t.keys)       // rid dropped, no-position dropped, dup merged
        val n = t.getValue("a479ef")
        assertEquals("N388KM", n.callsign)
        assertEquals(now - 400, n.posTimeMs)                   // fresher of the two duplicates
        assertEquals(7460.0, n.altBaroFt!!, 0.0)
        assertNull(n.altGeomFt)
        assertEquals(-300.0, n.vsFpm!!, 0.0)
        assertTrue(t.getValue("a00002").reportsGround)
        assertNull(t.getValue("a00002").altBaroFt)
        assertEquals(setOf("station"), n.sources)
    }

    @Test fun cloudReadsbGroundStringAndTs() {
        // real row shape from /api/live/adsb (2026-09-23)
        val j = """{"ts":1790206096679,"total":2,"ac":[
          {"hex":"407993","type":"tisb_icao","flight":"BAW8DS  ","r":"G-STBN","t":"B77W","alt_baro":"ground","gs":9.2,"lat":33.938919,"lon":-118.406805,"seen_pos":0.281,"seen":0.2,"src":"internet","feeder":"imac"},
          {"hex":"478854","flight":"SAS931  ","r":"LN-RKS","t":"A333","alt_baro":35125,"alt_geom":36675,"gs":445.6,"track":226.45,"baro_rate":-1344,"lat":34.754837,"lon":-116.048528,"seen_pos":0.36,"seen":0}]}"""
        val t = Parsers.parseReadsb(j, now, "cloud").associateBy { it.hex }
        assertEquals("BAW8DS", t.getValue("407993").callsign)
        assertTrue(t.getValue("407993").reportsGround)
        assertEquals(36675.0, t.getValue("478854").altGeomFt!!, 0.0)
        assertEquals(now - 360, t.getValue("478854").posTimeMs)
        assertEquals("SAS931", t.getValue("478854").displayId)
    }

    @Test fun ourDronesWithAirsense() {
        val j = """{"ts":1790189600000,"total":1,"drones":[{"_ageMs":1200,"ts":1790189598700,
          "drone":{"id":"1581F5FHD23","model":"M4T","callsign":"DEMO-1","pilot":"Demo Pilot"},
          "pos":{"lat":39.4135,"lon":-120.0432,"hdg":210.0,"altAglFt":2887,"altMslFt":8045},
          "motion":{"gsKt":3.1},
          "airsense":[{"icao":"A479EF","callsign":"N388KM","lat":39.40,"lon":-120.05,"altFt":7700,"gsKt":160,"trk":33}]}]}"""
        val p = Parsers.parseOurDrones(j, now)
        val d = p.drones.single()
        assertEquals("DEMO-1", d.name)
        assertEquals(8045.0, d.altMslFt!!, 0.0)
        assertEquals(now - 1200, d.posTimeMs)
        assertEquals(OwnshipSource.FLEET_FDA, d.source)
        val a = p.airsense.single()
        assertEquals("a479ef", a.hex); assertEquals(setOf("airsense"), a.sources)
        assertEquals(33.0, a.trackDeg!!, 0.0)
    }

    @Test fun emptyOurDrones() {
        val p = Parsers.parseOurDrones("""{"ts":1790206096682,"total":0,"drones":[]}""", now)
        assertTrue(p.drones.isEmpty())
    }

    @Test fun droneSenseSnapshot() {
        val j = """{"ts":1790189600000,"body":[{"id":"ds-1","callSign":"DEMO-1","latitude":39.4135,"longitude":-120.0432,
            "altitudeMsl":2452.1,"altitudeAgl":880.0,"lastUpdate":1790189598,"sensors":[{"rtsp_url":"rtsps://u:p@h/s"}]}]}"""
        val d = Parsers.parseDroneSense(j, now).single()
        assertEquals("DEMO-1", d.name)
        assertEquals(8045.0, d.altMslFt!!, 0.5)
        assertEquals(1790189598000L, d.posTimeMs)
        assertEquals(OwnshipSource.FLEET_DRONESENSE, d.source)
        assertTrue(Parsers.parseDroneSense("""{"ts":1,"body":[]}""", now).isEmpty())
        assertTrue(Parsers.parseDroneSense("""{"ts":0,"body":null}""", now).isEmpty())
    }

    @Test fun tfrFeed() {
        val j = """{"type":"FeatureCollection","features":[
          {"type":"Feature","properties":{"NOTAM_NUMBER":"0/0000","_ALT_L_VAL":0,"_ALT_L_UOM":"FT","_ALT_L_CODE":"ALT","_ALT_H_VAL":8500,"_ALT_H_UOM":"FT","_ALT_H_CODE":"ALT"},
           "geometry":{"type":"Polygon","coordinates":[[[-120.0667,39.4333],[-120.0333,39.4417],[-120.0167,39.4083],[-120.05,39.4],[-120.0667,39.4333]]]}},
          {"type":"Feature","properties":{"NOTAM_NUMBER":"6/1111","_ALT_L_VAL":0,"_ALT_L_UOM":"FT","_ALT_L_CODE":"HEI","_ALT_H_VAL":180,"_ALT_H_UOM":"FL","_ALT_H_CODE":"ALT"},
           "geometry":{"type":"MultiPolygon","coordinates":[[[[-121,38],[-121,38.1],[-120.9,38.1]]],[[[-122,38],[-122,38.1],[-121.9,38.1]]]]}},
          {"type":"Feature","properties":{"NAME":"DISNEYLAND","_ALT_NOTAM":true,"_SOURCE":"NDA"},
           "geometry":{"type":"Polygon","coordinates":[[[-117.9,33.8],[-117.9,33.81],[-117.91,33.81]]]}},
          {"type":"Feature","properties":{"NOTAM_NUMBER":"6/0000"},"geometry":null}]}"""
        val z = Parsers.parseTfrs(j)
        assertEquals(3, z.size)
        assertEquals("0/0000", z[0].name)
        assertEquals(AltLimit(8500.0, AltRef.MSL), z[0].ceiling)
        assertEquals(39.4333, z[0].polygons[0].outer[0].lat, 1e-9)   // [lon,lat] swapped correctly
        assertEquals(AltRef.AGL, z[1].floor.ref)
        assertEquals(AltLimit(18000.0, AltRef.MSL), z[1].ceiling)
        assertEquals(2, z[1].polygons.size)
        assertEquals("DISNEYLAND", z[2].name)
        assertEquals(AltLimit.UNKNOWN, z[2].ceiling)
        assertEquals("TFR 0 0000", z[0].spokenName)
    }

    @Test fun geofenceFile() {
        val j = """{"type":"Feature","properties":{"name":"Helibase"},"geometry":{"type":"Polygon","coordinates":[[[-120,39],[-120,39.1],[-119.9,39.1]]]}}"""
        val z = Parsers.parseGeofences(j, "file").single()
        assertEquals("Helibase", z.name); assertEquals(ZoneKind.GEOFENCE, z.kind)
        assertTrue(Parsers.parseGeofences("""{"type":"Point","coordinates":[1,2]}""", "x").isEmpty())
    }

    @Test fun circleZoneRadius() {
        val c = LatLon(39.4, -120.0)
        val z = Parsers.circleZone("c", "drone 1 nm", c, 1.0)
        z.polygons[0].outer.forEach { assertEquals(1.0, Geo.distanceNm(c, it), 0.005) }
        assertTrue(Geo.pointInPolygon(c, z.polygons[0]))
    }

    @Test fun mergerPrefersFresherPositionKeepsIdentity() {
        val a = Target(hex = "abc", callsign = "N1", lat = 1.0, lon = 1.0, posTimeMs = 100, sources = setOf("station"))
        val b = Target(hex = "abc", registration = "N1REG", lat = 2.0, lon = 2.0, posTimeMs = 200, sources = setOf("cloud"))
        val m = TrafficMerger.merge(listOf(a), listOf(b)).single()
        assertEquals(2.0, m.lat, 0.0)
        assertEquals("N1", m.callsign); assertEquals("N1REG", m.registration)
        assertEquals(setOf("station", "cloud"), m.sources)
        val m2 = TrafficMerger.merge(listOf(b), listOf(a.copy(posTimeMs = 300))).single()
        assertEquals(1.0, m2.lat, 0.0)
        assertFalse(TrafficMerger.within(listOf(a), LatLon(1.0, 1.0), 0.1).isEmpty())
        assertTrue(TrafficMerger.within(listOf(b), LatLon(1.0, 1.0), 30.0).isEmpty())
    }
}
