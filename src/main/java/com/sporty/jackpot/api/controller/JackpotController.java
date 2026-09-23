package com.sporty.jackpot.api.controller;

import com.sporty.jackpot.api.dto.JackpotResponse;
import com.sporty.jackpot.api.mapper.ApiMapper;
import com.sporty.jackpot.domain.model.Bet;
import com.sporty.jackpot.service.JackpotQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Jackpots: current pools and configuration. Produces JSON only (an unsatisfiable {@code Accept} header is rejected
 * with 406 before the query runs).
 */
@RestController
@RequestMapping(path = "/api/v1/jackpots", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Jackpots", description = "Jackpot pools and configuration")
public class JackpotController {

    private final JackpotQueryService queryService;
    private final ApiMapper apiMapper;

    public JackpotController(JackpotQueryService queryService, ApiMapper apiMapper) {
        this.queryService = queryService;
        this.apiMapper = apiMapper;
    }

    @GetMapping
    @Operation(summary = "List all jackpots")
    public List<JackpotResponse> getJackpots() {
        return queryService.findAll().stream().map(apiMapper::toResponse).toList();
    }

    @GetMapping("/{jackpotId}")
    @Operation(summary = "Get a jackpot")
    @ApiResponse(responseCode = "200", description = "The jackpot")
    @ApiResponse(responseCode = "404", description = "JACKPOT_NOT_FOUND")
    public JackpotResponse getJackpot(@PathVariable @Pattern(regexp = Bet.ID_REGEX) String jackpotId) {
        return apiMapper.toResponse(queryService.getJackpot(jackpotId));
    }
}
