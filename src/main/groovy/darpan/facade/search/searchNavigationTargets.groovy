import darpan.facade.common.FacadeSupport
import darpan.facade.search.NavigationSearchSupport

Map<String, Object> searchResult = NavigationSearchSupport.search(ec, query, types, pageIndex, pageSize)

results = searchResult.results
pagination = searchResult.pagination

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
