package me.qyh.blog.plugin.pte;

import org.springframework.context.ApplicationContext;
import org.springframework.web.context.WebApplicationContext;

import me.qyh.blog.core.plugin.PluginHandler;
import me.qyh.blog.template.service.TemplateService;

public class PtePluginHandler implements PluginHandler {

	@Override
	public void init(ApplicationContext applicationContext) {
		TemplateService templateService = applicationContext.getBean(TemplateService.class);
		((WebApplicationContext) applicationContext).getServletContext()
				.addListener(new PreviewTemplateEvitSessionListener(templateService));
	}

}
