package org.blaykie.nativeapp.citation.processor

import org.junit.Assert.*
import org.junit.Test

class FeatureGatedCitationProcessorProviderTest {
    @Test fun disabledProviderNeverConstructsHostOrProcessor() {
        var hosts = 0
        val provider = FeatureGatedCitationProcessorProvider(false) { hosts++; BuiltInApaProcessor }
        assertNull(provider.create("example"))
        assertNull(provider.create(""))
        assertEquals(0, hosts)
    }
    @Test fun enabledProviderOnlyCallsFactoryForValidStyle() {
        var hosts = 0
        val provider = FeatureGatedCitationProcessorProvider(true) { hosts++; BuiltInApaProcessor }
        assertNull(provider.create(" "))
        assertEquals(0, hosts)
        assertSame(BuiltInApaProcessor, provider.create("example"))
        assertEquals(1, hosts)
    }
}
