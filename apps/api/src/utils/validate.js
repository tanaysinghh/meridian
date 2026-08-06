import { ZodError } from 'zod';
import { httpError } from '../middleware/error.js';

// Validate req.body / req.query / req.params against a Zod schema.
// Replaces the property with the parsed value so downstream handlers get typed data.
export function validate({ body, query, params }) {
  return (req, _res, next) => {
    try {
      if (body) req.body = body.parse(req.body ?? {});
      if (query) req.query = query.parse(req.query ?? {});
      if (params) req.params = params.parse(req.params ?? {});
      next();
    } catch (err) {
      if (err instanceof ZodError) {
        const detail = err.issues.map(i => `${i.path.join('.') || '(root)'}: ${i.message}`).join('; ');
        return next(httpError(400, `invalid_request: ${detail}`));
      }
      next(err);
    }
  };
}
