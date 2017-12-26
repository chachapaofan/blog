package me.qyh.blog.web;

import java.io.Serializable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.support.RequestContextUtils;

import me.qyh.blog.core.config.Constants;
import me.qyh.blog.core.config.UrlHelper;
import me.qyh.blog.core.context.Environment;
import me.qyh.blog.core.entity.Lock;
import me.qyh.blog.core.exception.LockException;
import me.qyh.blog.core.exception.LogicException;
import me.qyh.blog.core.exception.RuntimeLogicException;
import me.qyh.blog.core.exception.SpaceNotFoundException;
import me.qyh.blog.core.exception.SystemException;
import me.qyh.blog.core.message.Message;
import me.qyh.blog.core.security.AuthencationException;
import me.qyh.blog.core.util.ExceptionUtils;
import me.qyh.blog.core.util.UrlUtils;
import me.qyh.blog.core.vo.JsonResult;
import me.qyh.blog.core.vo.LockBean;
import me.qyh.blog.template.Template;
import me.qyh.blog.template.render.RedirectException;
import me.qyh.blog.template.render.TemplateRenderException;
import me.qyh.blog.web.lock.LockHelper;
import me.qyh.blog.web.security.CsrfException;

public class WebExceptionResolver implements HandlerExceptionResolver {

	private static final Logger LOGGER = LoggerFactory.getLogger(WebExceptionResolver.class);

	@Autowired
	private UrlHelper urlHelper;

	private static final Message ERROR_400 = new Message("error.400", "请求异常");
	private static final Message ERROR_403 = new Message("error.403", "权限不足");
	private static final Message ERROR_404 = new Message("error.404", "请求不存在");
	private static final Message ERROR_405 = new Message("error.405", "请求方法不被允许");
	private static final Message ERROR_406 = new Message("error.406", "不被接受的请求");
	private static final Message ERROR_415 = new Message("error.405", "不支持的媒体类型");
	private static final Message ERROR_500 = Constants.SYSTEM_ERROR;

	private static final Message ERROR_NO_ERROR_MAPPING = new Message("error.noErrorMapping", "发生了一个错误，但是没有可供显示的错误页面");

	private final List<ExceptionHandler> handlers;

	public WebExceptionResolver() {
		handlers = Arrays.asList(new AuthencationExceptionHandler(), new TemplateRenderExceptionHandler(),
				new RedirectExceptionHandler(), new LockExceptionHandler(), new LogicExceptionHandler(),
				new RuntimeLogicExceptionHandler(), new SpaceNotFoundExceptionHandler(),
				new InvalidParamExceptionHandler(), new MethodArgumentNotValidExceptionHandler(),
				new HttpRequestMethodNotSupportedExceptionHandler(), new HttpMediaTypeNotAcceptableExceptionHandler(),
				new HttpMediaTypeNotSupportedExceptionHandler(), new MaxUploadSizeExceededExceptionHandler(),
				new MultipartExceptionHandler(), new NoHandlerFoundExceptionHandler());
	}

	@Override
	public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response, Object handler,
			Exception ex) {
		for (ExceptionHandler exceptionHandler : handlers) {
			if (exceptionHandler.match(ex)) {
				ModelAndView view = exceptionHandler.handler(request, response, ex);

				if (view == null) {
					throw new SystemException("ExceptionHandler的handler方法不应该返回null");
				}

				return view;
			}
		}

		if (!Webs.isClientAbortException(ex)) {
			String url = UrlUtils.buildFullRequestUrl(request);
			LOGGER.error("[" + url + "]" + ex.getMessage(), ex);
		}

		if (response.isCommitted()) {
			return new ModelAndView();
		}
		if (Webs.isAjaxRequest(request)) {
			return new ModelAndView(new JsonView(new JsonResult(false, ERROR_500)));
		}
		return getErrorForward(request, new ErrorInfo(ERROR_500, 500));
	}

	private interface ExceptionHandler {
		boolean match(Exception ex);

		ModelAndView handler(HttpServletRequest request, HttpServletResponse response, Exception ex);
	}

	private final class AuthencationExceptionHandler implements ExceptionHandler {

		@Override
		public boolean match(Exception ex) {
			return ex instanceof AuthencationException || ex instanceof CsrfException;
		}

		@Override
		public ModelAndView handler(HttpServletRequest request, HttpServletResponse response, Exception ex) {
			if (Webs.isAjaxRequest(request)) {
				return new ModelAndView(new JsonView(new JsonResult(false, ERROR_403)));
			}
			// 将链接放入
			if ("get".equalsIgnoreCase(request.getMethod())) {
				request.getSession().setAttribute(Constants.LAST_AUTHENCATION_FAIL_URL, getFullUrl(request));
			}

			return getErrorForward(request, new ErrorInfo(ERROR_403, 403));
		}
	}

	private final class TemplateRenderExceptionHandler implements ExceptionHandler {

		@Override
		public boolean match(Exception ex) {
			return ex instanceof TemplateRenderException;
		}

		@Override
		public ModelAndView handler(HttpServletRequest request, HttpServletResponse response, Exception ex) {
			TemplateRenderException tre = (TemplateRenderException) ex;
			if (!Template.isPreviewTemplate(tre.getTemplateName())) {
				LOGGER.error(tre.getMessage(), tre);
			}
			if (Webs.isAjaxRequest(request)) {
				return new ModelAndView(new JsonView(new JsonResult(false, tre.getRenderErrorDescription())));
			}
			Map<String, Object> model = new HashMap<>();
			model.put("description", tre.getRenderErrorDescription());
			return new ModelAndView("forward:/error/ui", model);
		}
	}

	private final class RedirectExceptionHandler implements ExceptionHandler {

		@Override
		public boolean match(Exception ex) {
			return ex instanceof RedirectException;
		}

		@Override
		public ModelAndView handler(HttpServletRequest request, HttpServletResponse response, Exception ex) {
			RedirectException re = (RedirectException) ex;

			if (Webs.isAjaxRequest(request)) {
				return new ModelAndView(new JsonView(new RedirectJsonResult(re.getUrl(), re.isPermanently())));
			}
			if (re.isPermanently()) {
				// 301
				response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
				response.setHeader("Location", re.getUrl());
				return new ModelAndView();
			} else {
				Message redirectMsg = re.getRedirectMsg();
				if (redirectMsg != null) {
					RequestContextUtils.getOutputFlashMap(request).put("redirect_page_msg", redirectMsg);
				}
				return new ModelAndView("redirect:" + re.getUrl());
			}

		}
	}

	private final class LockExceptionHandler implements ExceptionHandler {

		@Override
		public boolean match(Exception ex) {
			return ex instanceof LockException;
		}

		@Override
		public ModelAndView handler(HttpServletRequest request, HttpServletResponse response, Exception e) {
			LockException ex = (LockException) e;
			Lock lock = ex.getLock();
			String redirectUrl = getFullUrl(request);
			Message error = ex.getError();
			Map<String, Object> model = new HashMap<>();
			if (error != null) {
				model.put("error", error);
			}
			// 获取空间别名
			String alias = Webs.getSpaceFromRequest(request);
			LockHelper.storeLockBean(request, new LockBean(lock, ex.getLockResource(), redirectUrl, alias));
			if (alias != null) {
				return new ModelAndView("forward:/space/" + alias + "/unlock", model);
			} else {
				return new ModelAndView("forward:/unlock", model);
			}
		}
	}

	private final class LogicExceptionHandler implements ExceptionHandler {

		@Override
		public boolean match(Exception ex) {
			return ex instanceof LogicException;
		}

		@Override
		public ModelAndView handler(HttpServletRequest request, HttpServletResponse response, Exception e) {
			LogicException ex = (LogicException) e;
			if (Webs.isAjaxRequest(request)) {
				return new ModelAndView(new JsonView(new JsonResult(false, ex.getLogicMessage())));
			}

			return getErrorForward(request, new ErrorInfo(ex.getLogicMessage(), 200));
		}

	}

	private final class RuntimeLogicExceptionHandler implements ExceptionHandler {

		@Override
		public boolean match(Exception ex) {
			return ex instanceof RuntimeLogicException;
		}

		@Override
		public ModelAndView handler(HttpServletRequest request, HttpServletResponse response, Exception e) {
			RuntimeLogicException ex = (RuntimeLogicException) e;
			if (Webs.isAjaxRequest(request)) {
				return new ModelAndView(new JsonView(new JsonResult(false, ex.getLogicException().getLogicMessage())));
			}
			return getErrorForward(request, new ErrorInfo(ex.getLogicException().getLogicMessage(), 200));
		}
	}

	private final class SpaceNotFoundExceptionHandler implements ExceptionHandler {

		@Override
		public boolean match(Exception ex) {
			return ex instanceof SpaceNotFoundException;
		}

		@Override
		public ModelAndView handler(HttpServletRequest request, HttpServletResponse response, Exception ex) {
			return new ModelAndView("redirect:" + urlHelper.getUrl());
		}
	}

	private final class InvalidParamExceptionHandler implements ExceptionHandler {

		private final Class<?>[] exceptionClasses = { BindException.class, HttpMessageNotReadableException.class,
				HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
				MissingServletRequestPartException.class, TypeMismatchException.class };

		@Override
		public boolean match(Exception ex) {
			return ExceptionUtils.getFromChain(ex, exceptionClasses).isPresent();
		}

		@Override
		public ModelAndView handler(HttpServletRequest request, HttpServletResponse response, Exception ex) {
			LOGGER.debug(ex.getMessage(), ex);
			if (Webs.isAjaxRequest(request)) {
				return new ModelAndView(new JsonView(new JsonResult(false, ERROR_400)));
			}

			return getErrorForward(request, new ErrorInfo(ERROR_400, 400));
		}

	}

	private final class MethodArgumentNotValidExceptionHandler implements ExceptionHandler {

		@Override
		public boolean match(Exception ex) {
			return ex instanceof MethodArgumentNotValidException;
		}

		@Override
		public ModelAndView handler(HttpServletRequest request, HttpServletResponse response, Exception e) {
			MethodArgumentNotValidException ex = (MethodArgumentNotValidException) e;
			BindingResult result = ex.getBindingResult();
			List<ObjectError> errors = result.getAllErrors();
			for (ObjectError error : errors) {
				return new ModelAndView(new JsonView(new JsonResult(false,
						new Message(error.getCode(), error.getDefaultMessage(), error.getArguments()))));
			}
			throw new SystemException("抛出了MethodArgumentNotValidException，但没有发现任何错误");
		}

	}

	private final class HttpRequestMethodNotSupportedExceptionHandler implements ExceptionHandler {

		@Override
		public boolean match(Exception ex) {
			return ex instanceof HttpRequestMethodNotSupportedException;
		}

		@Override
		public ModelAndView handler(HttpServletRequest request, HttpServletResponse response, Exception ex) {
			if (Webs.isAjaxRequest(request)) {
				return new ModelAndView(new JsonView(new JsonResult(false, ERROR_405)));
			}
			return getErrorForward(request, new ErrorInfo(ERROR_405, 405));
		}

	}

	private final class HttpMediaTypeNotAcceptableExceptionHandler implements ExceptionHandler {

		@Override
		public boolean match(Exception ex) {
			return ex instanceof HttpMediaTypeNotAcceptableException;
		}

		@Override
		public ModelAndView handler(HttpServletRequest request, HttpServletResponse response, Exception ex) {
			if (Webs.isAjaxRequest(request)) {
				return new ModelAndView(new JsonView(new JsonResult(false, ERROR_406)));
			}

			return getErrorForward(request, new ErrorInfo(ERROR_406, 406));
		}

	}

	private final class HttpMediaTypeNotSupportedExceptionHandler implements ExceptionHandler {

		@Override
		public boolean match(Exception ex) {
			return ex instanceof HttpMediaTypeNotSupportedException;
		}

		@Override
		public ModelAndView handler(HttpServletRequest request, HttpServletResponse response, Exception ex) {
			if (Webs.isAjaxRequest(request)) {
				return new ModelAndView(new JsonView(new JsonResult(false, ERROR_415)));
			}

			return getErrorForward(request, new ErrorInfo(ERROR_415, 415));
		}

	}

	private final class MaxUploadSizeExceededExceptionHandler implements ExceptionHandler {

		@Override
		public boolean match(Exception ex) {
			return ex instanceof MaxUploadSizeExceededException;
		}

		@Override
		public ModelAndView handler(HttpServletRequest request, HttpServletResponse response, Exception e) {
			MaxUploadSizeExceededException ex = (MaxUploadSizeExceededException) e;
			if (Webs.isAjaxRequest(request)) {
				return new ModelAndView(new JsonView(new JsonResult(false, new Message("upload.overlimitsize",
						"超过允许的最大上传文件大小：" + ex.getMaxUploadSize() + "字节", ex.getMaxUploadSize()))));
			}
			return getErrorForward(request, new ErrorInfo(new Message("upload.overlimitsize",
					"超过允许的最大上传文件大小：" + ex.getMaxUploadSize() + "字节", ex.getMaxUploadSize()), 200));
		}

	}

	private class NoHandlerFoundExceptionHandler implements ExceptionHandler {

		@Override
		public boolean match(Exception ex) {
			return ex instanceof NoHandlerFoundException;
		}

		@Override
		public ModelAndView handler(HttpServletRequest request, HttpServletResponse response, Exception ex) {

			if (Webs.isAjaxRequest(request)) {
				return new ModelAndView(new JsonView(new JsonResult(false, ERROR_404)));
			}

			// 防止找不到错误页面重定向
			String mapping = request.getServletPath();
			String space = Webs.getSpaceFromRequest(request);
			String forwardMapping = "";
			if (space != null) {
				forwardMapping = "/space/" + space + "/error";
			} else {
				forwardMapping = "/error";
			}
			if (forwardMapping.equals(mapping)) {
				return new ModelAndView(new JsonView(new JsonResult(false, ERROR_NO_ERROR_MAPPING)));
			} else {
				return getErrorForward(request, new ErrorInfo(ERROR_404, 404));
			}
		}

	}

	private final class MultipartExceptionHandler implements ExceptionHandler {

		@Override
		public boolean match(Exception ex) {
			return ex instanceof MultipartException;
		}

		@Override
		public ModelAndView handler(HttpServletRequest request, HttpServletResponse response, Exception ex) {
			return new ModelAndView();
		}

	}

	private final class JsonView implements View {

		private final JsonResult result;

		private JsonView(JsonResult result) {
			super();
			this.result = result;
		}

		@Override
		public String getContentType() {
			return MediaType.APPLICATION_JSON_UTF8_VALUE;
		}

		@Override
		public void render(Map<String, ?> model, HttpServletRequest request, HttpServletResponse response)
				throws Exception {
			Webs.writeInfo(response, result);
		}

	}

	private String getFullUrl(HttpServletRequest request) {
		return UrlUtils.buildFullRequestUrl(request);
	}

	private ModelAndView getErrorForward(HttpServletRequest request, ErrorInfo error) {
		Map<String, Object> model = new HashMap<>();

		if (error != null) {

			/**
			 * 如果仍然包含重定向参数，防止和error冲突
			 */
			request.removeAttribute(DispatcherServlet.INPUT_FLASH_MAP_ATTRIBUTE);
			model.put(Constants.ERROR, error);
		}

		// 这里必须通过Environment.hasSpace()来判断，而不能通过Webs.getSpace(request) !=
		// null来判断
		// 因为如果空间是私人的，这里会造成循坏重定向
		if (Environment.hasSpace()) {
			return new ModelAndView("forward:/space/" + Environment.getSpaceAlias() + "/error", model,
					HttpStatus.valueOf(error.getCode()));
		} else {
			return new ModelAndView("forward:/error", model, HttpStatus.valueOf(error.getCode()));
		}
	}

	public static final class ErrorInfo implements Serializable {
		/**
		 * 
		 */
		private static final long serialVersionUID = 1L;
		private final Message message;
		private final int code;

		private ErrorInfo(Message message, int code) {
			super();
			this.message = message;
			this.code = code;
		}

		public Message getMessage() {
			return message;
		}

		public int getCode() {
			return code;
		}

	}

	public static final class RedirectJsonResult extends JsonResult {

		private final String url;
		private final boolean permanently;

		private RedirectJsonResult(String url, boolean permanently) {
			super(true);
			this.url = url;
			this.permanently = permanently;
		}

		public boolean isPermanently() {
			return permanently;
		}

		public String getUrl() {
			return url;
		}
	}

}
