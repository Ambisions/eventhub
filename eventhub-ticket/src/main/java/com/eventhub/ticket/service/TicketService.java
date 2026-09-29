package com.eventhub.ticket.service;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.eventhub.ticket.api.TicketIssuanceApi;
import com.eventhub.ticket.api.TicketCheckInCommandApi;
import com.eventhub.ticket.api.TicketCheckInQueryApi;
import com.eventhub.ticket.api.TicketQueryApi;
import com.eventhub.ticket.api.command.IssueTicketCommand;
import com.eventhub.ticket.api.error.TicketErrorCode;
import com.eventhub.ticket.api.error.TicketException;
import com.eventhub.ticket.api.error.TicketCheckInException;
import com.eventhub.ticket.api.error.TicketCheckInFailure;
import com.eventhub.ticket.api.model.TicketCheckInResult;
import com.eventhub.ticket.api.model.TicketCheckInTarget;
import com.eventhub.ticket.api.model.TicketStatus;
import com.eventhub.ticket.api.model.TicketView;
import com.eventhub.ticket.domain.TicketNumberGenerator;
import com.eventhub.ticket.mapper.TicketMapper;
import com.eventhub.ticket.repository.TicketEntity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketService implements TicketIssuanceApi, TicketQueryApi,
        TicketCheckInQueryApi, TicketCheckInCommandApi {

    private static final int MYSQL_DUPLICATE_KEY_ERROR_CODE = 1062;
    private static final String SQL_STATE_INTEGRITY_CONSTRAINT_VIOLATION = "23000";
    private static final String TICKET_NUMBER_UNIQUE_CONSTRAINT = "uk_ticket_ticket_no";
    private static final String ORDER_UNIQUE_CONSTRAINT = "uk_ticket_order";
    private static final String USER_SESSION_UNIQUE_CONSTRAINT = "uk_ticket_user_session";
    private static final Pattern TICKET_NUMBER_PATTERN = Pattern.compile("^TKT-[0-9A-F]{32}$");

    private final TicketMapper ticketMapper;
    private final TicketNumberGenerator ticketNumberGenerator;

    public TicketService(TicketMapper ticketMapper, TicketNumberGenerator ticketNumberGenerator) {
        this.ticketMapper = ticketMapper;
        this.ticketNumberGenerator = ticketNumberGenerator;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public TicketView issueTicket(IssueTicketCommand command) {
        validateIssueCommand(command);

        TicketEntity ticket = new TicketEntity();
        ticket.setTicketNo(ticketNumberGenerator.generate());
        ticket.setOrderId(command.orderId());
        ticket.setUserId(command.userId());
        ticket.setSessionId(command.sessionId());
        ticket.setStatus(TicketStatus.VALID);

        insertTicket(ticket);
        TicketEntity persisted = ticketMapper.selectById(ticket.getId());
        if (persisted == null) {
            throw new TicketException(TicketErrorCode.TICKET_ISSUANCE_FAILED);
        }
        return toView(persisted);
    }

    @Override
    public TicketView getTicket(long userId, String ticketNo) {
        if (userId <= 0 || ticketNo == null || !TICKET_NUMBER_PATTERN.matcher(ticketNo).matches()) {
            throw new TicketException(TicketErrorCode.TICKET_NOT_FOUND);
        }
        TicketEntity ticket = ticketMapper.selectOne(
                Wrappers.<TicketEntity>lambdaQuery()
                        .eq(TicketEntity::getTicketNo, ticketNo)
                        .eq(TicketEntity::getUserId, userId));
        if (ticket == null) {
            throw new TicketException(TicketErrorCode.TICKET_NOT_FOUND);
        }
        return toView(ticket);
    }

    @Override
    public TicketCheckInTarget resolveTarget(String ticketNo) {
        validateCheckInTicketNumber(ticketNo);
        TicketEntity ticket = ticketMapper.selectCheckInTarget(ticketNo);
        if (ticket == null) {
            throw checkInNotFound();
        }
        return new TicketCheckInTarget(ticket.getTicketNo(), ticket.getSessionId());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public TicketCheckInResult checkIn(String ticketNo, long sessionId) {
        validateCheckInTicketNumber(ticketNo);
        if (sessionId <= 0) {
            throw checkInNotFound();
        }

        int affectedRows = ticketMapper.markUsedIfValid(ticketNo, sessionId);
        if (affectedRows == 1) {
            return new TicketCheckInResult(ticketNo, sessionId, TicketStatus.USED);
        }
        if (affectedRows != 0) {
            throw new IllegalStateException("Ticket check-in affected an unexpected number of rows");
        }

        TicketEntity current = ticketMapper.selectCheckInState(ticketNo, sessionId);
        if (current == null) {
            throw checkInNotFound();
        }
        if (current.getStatus() == TicketStatus.USED) {
            throw new TicketCheckInException(TicketCheckInFailure.ALREADY_USED);
        }
        throw new IllegalStateException("Ticket remained VALID after a zero-row check-in update");
    }

    private void insertTicket(TicketEntity ticket) {
        try {
            int affectedRows = ticketMapper.insert(ticket);
            if (affectedRows != 1 || ticket.getId() == null) {
                throw new TicketException(TicketErrorCode.TICKET_ISSUANCE_FAILED);
            }
        } catch (DuplicateKeyException exception) {
            String constraint = duplicateConstraint(exception);
            if (ORDER_UNIQUE_CONSTRAINT.equals(constraint)
                    || USER_SESSION_UNIQUE_CONSTRAINT.equals(constraint)) {
                throw new TicketException(TicketErrorCode.TICKET_ALREADY_ISSUED, exception);
            }
            if (TICKET_NUMBER_UNIQUE_CONSTRAINT.equals(constraint)) {
                throw new TicketException(TicketErrorCode.TICKET_ISSUANCE_FAILED, exception);
            }
            throw exception;
        }
    }

    private static void validateIssueCommand(IssueTicketCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (command.orderId() <= 0) {
            throw new IllegalArgumentException("orderId must be positive");
        }
        if (command.userId() <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (command.sessionId() <= 0) {
            throw new IllegalArgumentException("sessionId must be positive");
        }
    }

    private static void validateCheckInTicketNumber(String ticketNo) {
        if (ticketNo == null || !TICKET_NUMBER_PATTERN.matcher(ticketNo).matches()) {
            throw checkInNotFound();
        }
    }

    private static TicketCheckInException checkInNotFound() {
        return new TicketCheckInException(TicketCheckInFailure.NOT_FOUND);
    }

    private static String duplicateConstraint(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && sqlException.getErrorCode() == MYSQL_DUPLICATE_KEY_ERROR_CODE
                    && SQL_STATE_INTEGRITY_CONSTRAINT_VIOLATION.equals(sqlException.getSQLState())) {
                String message = sqlException.getMessage();
                if (message == null) {
                    return null;
                }
                String normalizedMessage = message.toLowerCase(Locale.ROOT);
                if (normalizedMessage.contains(TICKET_NUMBER_UNIQUE_CONSTRAINT)) {
                    return TICKET_NUMBER_UNIQUE_CONSTRAINT;
                }
                if (normalizedMessage.contains(USER_SESSION_UNIQUE_CONSTRAINT)) {
                    return USER_SESSION_UNIQUE_CONSTRAINT;
                }
                if (normalizedMessage.contains(ORDER_UNIQUE_CONSTRAINT)) {
                    return ORDER_UNIQUE_CONSTRAINT;
                }
                return null;
            }
            current = current.getCause();
        }
        return null;
    }

    private static TicketView toView(TicketEntity ticket) {
        return new TicketView(
                ticket.getTicketNo(),
                ticket.getOrderId(),
                ticket.getSessionId(),
                ticket.getStatus(),
                ticket.getCreatedAt());
    }
}
