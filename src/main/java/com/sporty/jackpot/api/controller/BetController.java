package com.sporty.jackpot.api.controller;

import com.sporty.jackpot.api.dto.BetAcceptedResponse;
import com.sporty.jackpot.api.dto.BetEvaluationResponse;
import com.sporty.jackpot.api.dto.BetResponse;
import com.sporty.jackpot.api.dto.ContributionResponse;
import com.sporty.jackpot.api.dto.PlaceBetRequest;
import com.sporty.jackpot.api.mapper.ApiMapper;
import com.sporty.jackpot.domain.model.Bet;
import com.sporty.jackpot.service.BetPublishingService;
import com.sporty.jackpot.service.BetQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
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

    private final BetPublishingService publishingService;
    private final BetQueryService queryService;
    private final ApiMapper apiMapper;
    private final Clock clock;

    public BetController(BetPublishingService publishingService, BetQueryService queryService, ApiMapper apiMapper,
                         Clock clock) {
        this.publishingService = publishingService;
        this.queryService = queryService;
        this.apiMapper = apiMapper;
        this.clock = clock;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Publish a bet to Kafka",
            description = "Waits for the broker acknowledgement. Poll the Location URL for the processing result.")
    @ApiResponse(responseCode = "202", description = "Accepted; processed asynchronously")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, MALFORMED_REQUEST or INVALID_BET")
    @ApiResponse(responseCode = "503", description = "BET_PUBLISH_FAILED: outcome unknown, retry with the same betId")
    public ResponseEntity<BetAcceptedResponse> placeBet(@Valid @RequestBody PlaceBetRequest request) {
        Bet bet = apiMapper.toBet(request, clock.instant());
        Instant acceptedAt = publishingService.publish(bet);
        return ResponseEntity.accepted()
                .location(UriComponentsBuilder.fromPath("/api/v1/bets/{betId}").buildAndExpand(bet.betId()).toUri())
                .body(apiMapper.toAcceptedResponse(bet, acceptedAt));
    }

    @GetMapping("/{betId}")
    @Operation(summary = "Get a processed bet")
    @ApiResponse(responseCode = "200", description = "The bet was processed")
    @ApiResponse(responseCode = "404", description = "BET_NOT_FOUND: unknown or not processed yet")
    public BetResponse getBet(@PathVariable @Pattern(regexp = Bet.ID_REGEX) String betId) {
        return apiMapper.toResponse(queryService.getBet(betId));
    }

    @GetMapping("/{betId}/contribution")
    @Operation(summary = "Get a bet's jackpot contribution")
    @ApiResponse(responseCode = "200", description = "The bet contributed")
    @ApiResponse(responseCode = "404", description = "BET_NOT_FOUND: unknown or not processed yet")
    @ApiResponse(responseCode = "422", description = "BET_NOT_CONTRIBUTING: no matching jackpot")
    public ContributionResponse getContribution(@PathVariable @Pattern(regexp = Bet.ID_REGEX) String betId) {
        return apiMapper.toResponse(queryService.getContribution(betId));
    }

    @GetMapping("/{betId}/evaluation")
    @Operation(summary = "Did the bet win the jackpot, and the reward",
            description = "Returns the stored result of the single evaluation made while processing the bet.")
    @ApiResponse(responseCode = "200", description = "The bet was evaluated")
    @ApiResponse(responseCode = "404", description = "BET_NOT_FOUND: unknown or not processed yet")
    @ApiResponse(responseCode = "422", description = "BET_NOT_CONTRIBUTING: no matching jackpot")
    public BetEvaluationResponse getEvaluation(@PathVariable @Pattern(regexp = Bet.ID_REGEX) String betId) {
        return apiMapper.toResponse(queryService.getEvaluation(betId));
    }
}
