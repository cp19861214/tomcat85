package org.sourcecode.tomcat.common;

import java.io.IOException;
import java.io.Writer;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;

public class CommonErrorReportValve extends ErrorReportValve {

	protected Log log = LogFactory.getLog(getClass());

	private String errorPage = "/error.html";

	@Override
	protected void report(Request request, Response response, Throwable throwable) {
		String stackTrace = getPartialServletStackTrace(throwable);
		int statusCode = response.getStatus();
		log.error("statusCode = " + statusCode + ",exception: " + stackTrace);
		try {
			try {
				response.setContentType("text/html");
				response.setCharacterEncoding("utf-8");
			} catch (Throwable t) {
				ExceptionUtils.handleThrowable(t);
			}
			Writer writer = response.getReporter();
			if (writer != null) {
				// If writer is null, it's an indication that the response has
				// been hard committed already, which should never happen
				writer.write("<html><body>System is Occur a Error Please retry ..." + "<script>location.href='"
						+ errorPage + "';" + "</script></body></html>");
				response.finishResponse();
			}
		} catch (IOException e) {
			// Ignore
		} catch (IllegalStateException e) {
			// Ignore
		}
	}

}
