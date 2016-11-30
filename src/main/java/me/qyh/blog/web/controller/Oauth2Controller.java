/*
 * Copyright 2016 qyh.me
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package me.qyh.blog.web.controller;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

import me.qyh.blog.config.Constants;
import me.qyh.blog.oauth2.Oauth2;
import me.qyh.blog.oauth2.Oauth2Provider;
import me.qyh.blog.oauth2.OauthUser;
import me.qyh.blog.oauth2.UserInfo;
import me.qyh.blog.service.OauthService;
import me.qyh.util.UUIDs;
import me.qyh.util.Validators;

@RequestMapping("oauth2/{id}")
@Controller
public class Oauth2Controller extends BaseController {

	private static final String STATE = "state";
	private static final String REFERER_URL = "refererUrl";

	@Autowired
	private Oauth2Provider provider;
	@Autowired
	private OauthService oauthUserService;

	@RequestMapping("login")
	public String login(@PathVariable("id") String id, HttpSession session,
			@RequestHeader(value = "referer", required = false) final String referer) {
		if (!provider.isEnable()) {
			return "redirect:" + referer;
		}
		Oauth2 oauth2 = provider.getOauth2(id);
		if (oauth2 != null) {
			String state = UUIDs.uuid();
			session.setAttribute(STATE, state);
			if (referer != null) {
				session.setAttribute(REFERER_URL, referer);
			}
			return "redirect:" + oauth2.getAuthorizeUrl(state);
		}
		return "redirect:/";
	}

	@RequestMapping("success")
	public String success(@PathVariable("id") String id, HttpServletRequest request) {
		if (!provider.isEnable()) {
			return "redirect:/";
		}
		Oauth2 oauth2 = provider.getOauth2(id);
		if (oauth2 != null) {
			if (!validState(request, oauth2)) {
				return "redirect:/";
			}
			String code = request.getParameter("code");
			if (Validators.isEmptyOrNull(code, true)) {
				return "redirect:/";
			}
			UserInfo user = oauth2.getUserInfo(code);
			if (user != null) {
				OauthUser oauthUser = new OauthUser(user);
				oauthUser.setServerId(id);
				OauthUser updated = oauthUserService.insertOrUpdate(oauthUser);
				HttpSession session = request.getSession();
				session.setAttribute(Constants.OAUTH_SESSION_KEY, updated);
				String referer = (String) session.getAttribute(REFERER_URL);
				if (referer != null) {
					return "redirect:" + referer;
				}
			}
		}
		return "redirect:/";
	}

	private boolean validState(HttpServletRequest request, Oauth2 oauth2) {
		HttpSession session = request.getSession(false);
		if (session == null) {
			return false;
		}
		String state = (String) session.getAttribute(STATE);
		if (state == null) {
			return false;
		}
		String requestState = request.getParameter(STATE);
		if (!state.equals(requestState)) {
			return false;
		}
		session.removeAttribute(STATE);
		return true;
	}

}
