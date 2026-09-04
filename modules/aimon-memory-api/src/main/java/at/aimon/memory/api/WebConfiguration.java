package at.aimon.memory.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import at.aimon.memory.api.security.AuthInterceptor;
import at.aimon.memory.api.security.MemoryPrincipal;

/** Installs the auth interceptor and lets controllers take a {@link MemoryPrincipal} parameter. */
@Configuration
public class WebConfiguration implements WebMvcConfigurer {

    private final AuthInterceptor auth;

    public WebConfiguration(AuthInterceptor auth) {
        this.auth = auth;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auth).addPathPatterns("/v1/**");
    }

    @Override
    public void addArgumentResolvers(java.util.List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new PrincipalResolver());
    }

    private static final class PrincipalResolver implements HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return MemoryPrincipal.class.equals(parameter.getParameterType());
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                NativeWebRequest request, WebDataBinderFactory binderFactory) {
            return request.getAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE, NativeWebRequest.SCOPE_REQUEST);
        }
    }
}
