/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package com.linagora.tmail.webadmin;

import static org.apache.james.webadmin.Constants.SEPARATOR;
import static org.eclipse.jetty.http.HttpStatus.NOT_FOUND_404;

import javax.inject.Inject;

import org.apache.james.core.Username;
import org.apache.james.user.api.UsersRepository;
import org.apache.james.user.api.UsersRepositoryException;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.utils.ErrorResponder;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.apache.james.webadmin.utils.Responses;

import com.linagora.tmail.rate.limiter.api.RateLimitationPlanRepository;
import com.linagora.tmail.rate.limiter.api.RateLimitingPlanId;
import com.linagora.tmail.rate.limiter.api.RateLimitingPlanNotFoundException;
import com.linagora.tmail.rate.limiter.api.RateLimitingPlanUserRepository;
import com.linagora.tmail.webadmin.model.RateLimitingPlanIdResponse;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import spark.Request;
import spark.Route;
import spark.Service;

public class RateLimitPlanUserRoutes implements Routes {
    private static final String USERNAME_PARAM = ":username";
    private static final String PLAN_ID_PARAM = ":planId";
    private static final String RATE_LIMIT_BASE_PATH = SEPARATOR + "rate-limit-plans";
    private static final String USERS_PATH = SEPARATOR + "users";
    private static final String ATTACH_PLAN_TO_USER_PATH = USERS_PATH + SEPARATOR + USERNAME_PARAM + RATE_LIMIT_BASE_PATH
        + SEPARATOR + PLAN_ID_PARAM;
    private static final String GET_USERS_OF_PLAN_PATH = RATE_LIMIT_BASE_PATH + SEPARATOR + PLAN_ID_PARAM + USERS_PATH;
    private static final String REVOKE_PLAN_OF_USER_PATH = USERS_PATH + SEPARATOR + USERNAME_PARAM + RATE_LIMIT_BASE_PATH;
    private static final String GET_PLAN_OF_USER_PATH = USERS_PATH + SEPARATOR + USERNAME_PARAM + RATE_LIMIT_BASE_PATH;

    private final RateLimitingPlanUserRepository planUserRepository;
    private final RateLimitationPlanRepository planRepository;
    private final UsersRepository usersRepository;
    private final JsonTransformer jsonTransformer;

    @Inject
    public RateLimitPlanUserRoutes(RateLimitingPlanUserRepository planUserRepository, RateLimitationPlanRepository planRepository,
                                   UsersRepository usersRepository, JsonTransformer jsonTransformer) {
        this.planUserRepository = planUserRepository;
        this.planRepository = planRepository;
        this.usersRepository = usersRepository;
        this.jsonTransformer = jsonTransformer;
    }

    @Override
    public String getBasePath() {
        return RATE_LIMIT_BASE_PATH;
    }

    @Override
    public void define(Service service) {
        service.put(ATTACH_PLAN_TO_USER_PATH, attachPlanToUser(), jsonTransformer);
        service.get(GET_USERS_OF_PLAN_PATH, getUsersOfPlan(), jsonTransformer);
        service.get(GET_PLAN_OF_USER_PATH, getPlanOfUser(), jsonTransformer);
        service.delete(REVOKE_PLAN_OF_USER_PATH, revokePlanOfUser(), jsonTransformer);
    }

    private Route attachPlanToUser() {
        return (request, response) -> {
            Username username = extractUsername(request);
            RateLimitingPlanId planId = extractPlanId(request);
            userPreconditions(username);
            planIdPreconditions(planId);
            return Mono.from(planUserRepository.applyPlan(username, planId))
                .then(Mono.just(Responses.returnNoContent(response)))
                .block();
        };
    }

    private Route getUsersOfPlan() {
        return (request, response) -> {
            RateLimitingPlanId planId = extractPlanId(request);
            planIdPreconditions(planId);
            return Flux.from(planUserRepository.listUsers(planId))
                .map(Username::asString)
                .collectList()
                .block();
        };
    }

    private Route getPlanOfUser() {
        return (request, response) -> {
            Username username = extractUsername(request);
            userPreconditions(username);
            return Mono.from(planUserRepository.getPlanByUser(username))
                .map(planId -> new RateLimitingPlanIdResponse(planId.value().toString()))
                .onErrorResume(RateLimitingPlanNotFoundException.class, e -> {
                    throw ErrorResponder.builder()
                        .statusCode(NOT_FOUND_404)
                        .type(ErrorResponder.ErrorType.NOT_FOUND)
                        .message(String.format("User %s does not have a plan", username.asString()))
                        .haltError();
                })
                .block();
        };
    }

    private Route revokePlanOfUser() {
        return (request, response) -> {
            Username username = extractUsername(request);
            userPreconditions(username);
            return Mono.from(planUserRepository.revokePlan(username))
                .then(Mono.just(Responses.returnNoContent(response)))
                .block();
        };
    }

    private Username extractUsername(Request request) {
        return Username.of(request.params(USERNAME_PARAM));
    }

    private RateLimitingPlanId extractPlanId(Request request) {
        return RateLimitingPlanId.parse(request.params(PLAN_ID_PARAM));
    }

    public void userPreconditions(Username username) throws UsersRepositoryException {
        if (!usersRepository.contains(username)) {
            throw ErrorResponder.builder()
                .statusCode(NOT_FOUND_404)
                .type(ErrorResponder.ErrorType.NOT_FOUND)
                .message(String.format("User %s does not exist", username.asString()))
                .haltError();
        }
    }

    public void planIdPreconditions(RateLimitingPlanId planId) {
        if (!Mono.from(planRepository.planExists(planId)).block()) {
            throw ErrorResponder.builder()
                .statusCode(NOT_FOUND_404)
                .type(ErrorResponder.ErrorType.NOT_FOUND)
                .message(String.format("Plan id %s does not exist", planId.value().toString()))
                .haltError();
        }
    }
}
