package com.prevention.fraud.validationflow.config.security;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects bodies above {@code maxBytes} with 413 {@code ErrorResponse}-shaped JSON. Declared Content-Length is checked
 * first; otherwise (chunked) at most {@code maxBytes + 1} bytes are buffered, so memory use is bounded either way.
 */
class BodySizeLimitFilter extends OncePerRequestFilter {

	private final long maxBytes;

	BodySizeLimitFilter(long maxBytes) {
		this.maxBytes = maxBytes;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (request.getContentLengthLong() > maxBytes) {
			tooLarge(response);
			return;
		}
		if (request.getContentLengthLong() >= 0) { // declared and within the limit; the container enforces the length
			chain.doFilter(request, response);
			return;
		}
		byte[] body = request.getInputStream().readNBytes((int) maxBytes + 1);
		if (body.length > maxBytes) {
			tooLarge(response);
			return;
		}
		chain.doFilter(new HttpServletRequestWrapper(request) {
			@Override
			public ServletInputStream getInputStream() {
				var in = new ByteArrayInputStream(body);
				return new ServletInputStream() {
					@Override
					public int read() {
						return in.read();
					}

					@Override
					public int read(byte[] b, int off, int len) {
						return in.read(b, off, len);
					}

					@Override
					public boolean isFinished() {
						return in.available() == 0;
					}

					@Override
					public boolean isReady() {
						return true;
					}

					@Override
					public void setReadListener(ReadListener l) {
						throw new UnsupportedOperationException();
					}
				};
			}

			@Override
			public java.io.BufferedReader getReader() {
				String enc = getCharacterEncoding();
				return new java.io.BufferedReader(new java.io.InputStreamReader(new ByteArrayInputStream(body),
						enc == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(enc)));
			}
		}, response);
	}

	private void tooLarge(HttpServletResponse response) throws IOException {
		response.setStatus(413);
		response.setContentType("application/json");
		response.getWriter().write("{\"code\":\"PAYLOAD_TOO_LARGE\",\"retryable\":false,\"details\":[\"request body exceeds "
				+ maxBytes + " bytes\"]}");
	}

}
