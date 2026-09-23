package com.sporty.jackpot.service;

import com.sporty.jackpot.domain.model.Jackpot;
import com.sporty.jackpot.exception.JackpotNotFoundException;
import com.sporty.jackpot.persistence.mapper.EntityMapper;
import com.sporty.jackpot.persistence.repository.JackpotRepository;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only jackpot lookups.
 */
@Service
@Transactional(readOnly = true)
public class JackpotQueryService {

    private final JackpotRepository jackpotRepository;
    private final EntityMapper entityMapper;

    public JackpotQueryService(JackpotRepository jackpotRepository, EntityMapper entityMapper) {
        this.jackpotRepository = jackpotRepository;
        this.entityMapper = entityMapper;
    }

    /**
     * @return all jackpots ordered by id
     */
    public List<Jackpot> findAll() {
        return jackpotRepository.findAll(Sort.by("id")).stream()
                .map(entityMapper::toJackpot)
                .toList();
    }

    /**
     * @param jackpotId jackpot id
     * @return the jackpot
     * @throws JackpotNotFoundException when it does not exist
     */
    public Jackpot getJackpot(String jackpotId) {
        return jackpotRepository.findById(jackpotId)
                .map(entityMapper::toJackpot)
                .orElseThrow(() -> new JackpotNotFoundException(jackpotId));
    }
}
