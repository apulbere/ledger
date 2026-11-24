package ledger.query.search;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SearchController {

    private final TransactionSearch search;

    SearchController(TransactionSearch search) {
        this.search = search;
    }

    // Parameters arrive as text and are checked in SearchRequest, so each failure names exactly one parameter.
    @GetMapping("/accounts/{accountId}/transactions")
    TransactionSearch.Page searchByAccount(
            @PathVariable String accountId,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String occurredFrom,
            @RequestParam(required = false) String occurredTo,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        return search.search(SearchRequest.of(accountId, currency, occurredFrom, occurredTo, limit, cursor));
    }
}
