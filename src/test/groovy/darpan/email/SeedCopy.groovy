package darpan.email

import groovy.xml.XmlSlurper

/** DAR-BE-303: the shipped words for tests that run no Moqui: a reader over data/EmailCopySeedData.xml's defaults. */
class SeedCopy {
    static EmailCopyReader reader(String templateId) {
        Map<String, String> seed = [:]
        new XmlSlurper().parse(new File("data/EmailCopySeedData.xml")).children().each { node ->
            if (node.name() == "darpan.email.EmailCopyBlock") seed.put(node.@emailTemplateId.text() + "/" + node.@blockId.text(), node.@defaultText.text())
        }
        return EmailCopyReader.ofDefaults(templateId, seed)
    }
}
