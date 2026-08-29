package de.westnordost.streetcomplete.data.maptiles

import de.westnordost.streetcomplete.data.osm.mapdata.BoundingBox

/** Does not actually pre-download any map tiles yet.
 *
 *  Pre-downloading the tiles for an area is only a nicety - it makes the map show up instantly in
 *  an area the user downloaded before, also when offline. Everything else about a download works
 *  without it, so it is fine to do nothing here for now.
 *
 *  maplibre-compose exposes offline packs in its shared API since 0.15.0, so this can be
 *  implemented properly. */
class IosMapTilesDownloader : MapTilesDownloader {
    override suspend fun download(bbox: BoundingBox) {}

    override suspend fun clear() {}
}
