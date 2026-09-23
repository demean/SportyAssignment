package com.sporty.jackpot.api.controller;

import com.sporty.jackpot.api.dto.BetAcceptedResponse;
import com.sporty.jackpot.api.dto.BetEvaluationResponse;
import com.sporty.jackpot.api.dto.BetResponse;
import com.sporty.jackpot.api.dto.ContributionResponse;
import com.sporty.jackpot.api.dto.PlaceBetRequest;
import com.sporty.jackpot.api.mapper.ApiMapper;
import com.sporty.jackpot.domain.model.Bet;
import com.sporty.jackpot.service.BetPlacementService;
import com.sporty.jackpot.service.BetQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Bets: publish ({@code 202 Accepted}, processed asynchronously) and look up the processing results.
 * Every endpoint produces JSON only, so an unsatisfiable {@code Accept} header is rejected with 406 before the
 * handler runs (never after a bet has been published).
 */
@RestController
@RequestMapping(path = "/api/v1/bets", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Bets", description = "Publish bets and query their contribution and reward evaluation")
public class BetController {

    static final String INVALID_ID_DESCRIPTION = "VALIDATION_FAILED: the id does not match " + Bet.ID_REGEX;
    static final String TEMPORARILY_UNAVAILABLE_DESCRIPTION =
            "TEMPORARILY_UNAVAILABLE: transient database failure, retry";

    private final BetPlacementService placementService;
    private final BetQueryService queryService;
    private final ApiMapper apiMapper;

    public BetController(BetPlacementService placementService, BetQueryService queryService, ApiMapper apiMapper) {
        this.placementService = placementService;
        this.queryService = queryService;
        this.apiMapper = apiMapper;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Publish a bet to Kafka",
            description = "Waits for the broker acknowledgement. Poll the Location URL for the processing result.")
    @ApiResponse(responseCode = "202", description = "Accepted; processed asynchronously",
            headers = @Header(name = HttpHeaders.LOCATION, description = "The bet's URL: poll it until it answers 200",
                    schema = @Schema(type = "string", format = "uri-reference")))
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, MALFORMED_REQUEST or INVALID_BET")
    @ApiResponse(responseCode = "415", description = "UNSUPPORTED_MEDIA_TYPE: the body is not application/json")
    @ApiResponse(responseCode = "503", description = "BET_PUBLISH_FAILED: outcome unknown, retry with the same betId")
    public ResponseEntity<BetAcceptedResponse> placeBet(@Valid @RequestBody PlaceBetRequest request) {
        Bet bet = placementService.place(request.betId(), request.userId(), request.jackpotId(), request.betAmount());
        return ResponseEntity.accepted()
                .location(UriComponentsBuilder.fromPath("/api/v1/bets/{betId}").buildAndExpand(bet.betId()).toUri())
                .body(apiMapper.toAcceptedResponse(bet));
    }

    @GetMapping("/{betId}")
    @Operation(summary = "Get a processed bet")
    @ApiResponse(responseCode = "200", description = "The bet was processed")
    @ApiResponse(responseCode = "400", description = INVALID_ID_DESCRIPTION)
    @ApiResponse(responseCode = "404", description = "BET_NOT_FOUND: unknown or not processed yet")
    @ApiResponse(responseCode = "503", description = TEMPORARILY_UNAVAILABLE_DESCRIPTION)
    public BetResponse getBet(@PathVariable @Pattern(regexp = Bet.ID_REGEX) String betId) {
        return apiMapper.toResponse(queryService.getBet(betId));
    }

    @GetMapping("/{betId}/contribution")
    @Operation(summary = "Get a bet's jackpot contribution")
    @ApiResponse(responseCode = "200", description = "The bet contributed")
    @ApiResponse(responseCode = "400", description = INVALID_ID_DESCRIPTION)
    @ApiResponse(responseCode = "404", description = "BET_NOT_FOUND: unknown or not processed yet")
    @ApiResponse(responseCode = "422", description = "BET_NOT_CONTRIBUTING: no matching jackpot")
    @ApiResponse(responseCode = "503", description = TEMPORARILY_UNAVAILABLE_DESCRIPTION)
    public ContributionResponse getContribution(@PathVariable @Pattern(regexp = Bet.ID_REGEX) String betId) {
        return apiMapper.toResponse(queryService.getContribution(betId));
    }

    @GetMapping("/{betId}/evaluation")
    @Operation(summary = "Did the bet win the jackpot, and the reward",
            description = "Returns the stored result of the single evaluation made while processing the bet.")
    @ApiResponse(responseCode = "200", description = "The bet was evaluated")
    @ApiResponse(responseCode = "400", description = INVALID_ID_DESCRIPTION)
    @ApiResponse(responseCode = "404", description = "BET_NOT_FOUND: unknown or not processed yet")
    @ApiResponse(responseCode = "422", description = "BET_NOT_CONTRIBUTING: no matching jackpot")
    @ApiResponse(responseCode = "503", description = TEMPORARILY_UNAVAILABLE_DESCRIPTION)
    public BetEvaluationResponse getEvaluation(@PathVariable @Pattern(regexp = Bet.ID_REGEX) String betId) {
        return apiMapper.toResponse(queryService.getEvaluation(betId));
    }
}
