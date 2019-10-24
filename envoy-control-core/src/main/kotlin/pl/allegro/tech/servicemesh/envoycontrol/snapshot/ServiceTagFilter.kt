package pl.allegro.tech.servicemesh.envoycontrol.snapshot


interface ServiceTagFilter {
    fun filterTagsForRouting(tags: Set<String>): Set<String>
    fun isAllowedToMatchOnTwoTags(serviceName: String): Boolean
}

class DefaultServiceTagFilter : ServiceTagFilter {
    override fun filterTagsForRouting(tags: Set<String>): Set<String> = tags
    override fun isAllowedToMatchOnTwoTags(serviceName: String): Boolean = false
}