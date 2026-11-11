package com.onlineexam.platform.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onlineexam.platform.controller.ApiResponse;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE+1)
class JsonSizeConfiguration extends OncePerRequestFilter {
    private static final int MAX=2*1024*1024;
    private final ObjectMapper json;
    JsonSizeConfiguration(ObjectMapper json) { this.json=json; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String contentType=request.getContentType()==null?"":request.getContentType().split(";",2)[0].strip().toLowerCase(java.util.Locale.ROOT);
        if(!contentType.equals("application/json")&&!contentType.endsWith("+json")) { chain.doFilter(request,response);return; }
        if(request.getContentLengthLong()>MAX) {
            response.setStatus(400);response.setContentType("application/json");json.writeValue(response.getOutputStream(),ApiResponse.error("VALIDATION_ERROR","JSON 请求不得超过 2 MiB。"));return;
        }
        var wrapped=new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() throws IOException {
                var original=super.getInputStream();
                return new ServletInputStream() {
                    int count;
                    private void add(int size) throws IOException { if(size>0&&(count+=size)>MAX)throw new IOException("JSON request limit exceeded"); }
                    @Override public int read() throws IOException { int value=original.read();add(value<0?0:1);return value; }
                    @Override public int read(byte[] bytes,int offset,int length) throws IOException { int size=original.read(bytes,offset,length);add(size);return size; }
                    @Override public boolean isFinished() { return original.isFinished(); }
                    @Override public boolean isReady() { return original.isReady(); }
                    @Override public void setReadListener(ReadListener listener) { original.setReadListener(listener); }
                };
            }
        };
        chain.doFilter(wrapped,response);
    }
}
