import darpan.facade.common.FacadeSupport

String enumType = FacadeSupport.normalize(enumTypeId)
if (!enumType) {
    ec.message.addError("enumTypeId is required")
}

List<Map> enumOptions = []
if (!ec.message.hasError()) {
    (ec.entity.find("moqui.basic.Enumeration")
        .condition("enumTypeId", enumType)
        .orderBy("sequenceNum,description,enumId")
        .useCache(false)
        .list() ?: [])
        .each { item ->
            enumOptions.add([
                enumId: item.enumId,
                enumCode: item.enumCode,
                description: item.description,
                sequenceNum: item.sequenceNum,
                label: FacadeSupport.enumLabel(item)
            ])
        }
}

options = enumOptions

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
